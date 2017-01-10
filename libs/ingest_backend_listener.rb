# Information:
# The glue script to get the parameters from jenkins and call Jemter to run the laod test, Created at 2017/01/13 by Neil.Wang 
# Usage:
# ruby ingest_beckend_listener.rb
# Precondition:
# 1. Make sure the ruby gems nokogiri, json and rubyzip are installed
# 2. The files and required ruby file should be placed at the root folder of the workspace
require 'rubygems'
require 'json'
require 'nokogiri'
require 'fileutils'
require_relative 'zip_helper.rb'
require_relative 'jmeter_helper.rb'

module Utilities
	class JMXHelper

		# The contructor method, need a parameter of the teamplate path
		def initialize(testplanTemplate)
			@current_path = File.absolute_path(__FILE__)
			if(File.exist?(testplanTemplate))
				if(File.extname(testplanTemplate) == ".jmx")
					@testplanTemplate = testplanTemplate
					doc_content = File.new(testplanTemplate).read()
					@doc = Nokogiri.XML(doc_content)
					@testplan_absolutepath = File.absolute_path(testplanTemplate)
					@root_target_path = File.dirname(@testplan_absolutepath)
				elsif(File.extname(testplanTemplate) == '.zip')
					# unzip the tar to current folder
					ZipHelper.unzip(testplanTemplate, File.dirname(testplanTemplate))
					Dir.foreach(File.dirname(testplanTemplate)) do |item|
						file_item = File.join(File.dirname(testplanTemplate), item)
						if(File.file?(file_item) && File.extname(file_item) == '.jmx')
							@testplanTemplate = file_item
						end
					end
					if(@testplanTemplate == nil || @testplanTemplate == '')
						raise "Could not find any valid test plan file within the zip package"
					end
					doc_content = File.new(@testplanTemplate).read()
					@doc = Nokogiri.XML(doc_content)
					@testplan_absolutepath = File.absolute_path(@testplanTemplate)
					@root_target_path = File.dirname(@testplan_absolutepath)
				else
					raise "Not support the format of #{File.extname(testplanTemplate)} yet, please upload your test plan with extension of .jmx or .zip"
				end
			else
				raise "The test plan #{testplanTemplate} does not exist"
			end
		end

	    # Modify the load settings of the test plan
		def modify_load_parameters(ramp_time, num_threads, duration, loops)
			if(ramp_time != nil)
				modify_warmup_period(ramp_time)
			end
			if(num_threads != nil)
				modify_thread_num(num_threads)
			end
			if(duration != nil)
				modify_duration(duration)
			end
			if(loops != nil)
				modify_loops(loops)
			end
		end

		#ingest the backend listener into the test plan's thread group
		def ingest_backend_listener()
			backend_listener = File.dirname(@current_path) + "/backendlistener.xml"
			backend_listener_segment = File.new(backend_listener).read()
			@doc.xpath("//jmeterTestPlan/hashTree/hashTree/hashTree").each do |thread_group|
				#thread_group.add_next_sibling(backend_listener_segment)
				thread_group.add_child(backend_listener_segment)
			end
		end

		# Save the modified test plan to a new one
		def generate_new_testplan(testplanName=nil)
			if(testplanName != nil && testplanName != "")
				File.new(testplanName, "w").write(@doc.to_xml)
				puts "New test plan #{ testplanName } is created successfully"
				return File.absolute_path(testplanName)
			else
				newTestPlanName = File.join(@root_target_path, "#{ File.basename(@testplan_absolutepath, '.jmx') }_threads_#{get_num_threads()}_loops_#{get_loops()}_duration_#{get_duration}_ramp_#{get_ramp_time()}.jmx" )
				File.new(newTestPlanName, "w").write(@doc.to_xml)
				puts "New test plan #{ newTestPlanName } is created successfully"
				return File.absolute_path(newTestPlanName)
			end
		end

		# The class method to generate more test plans based on the template, mainly focus on the load thread adjustment
		def JMXHelper.generate_testplans(testplan_template, start_num_threads, increase_step, target_num_threads)
			test_plans = []
			num_threads = start_num_threads
			while(num_threads <= target_num_threads)
				helper = JMXHelper.new(testplan_template)
				helper.modify_load_parameters(nil, num_threads, nil, nil)
				helper.ingest_backend_listener
				new_test_plan = helper.generate_new_testplan
				test_plans << File.absolute_path(new_test_plan)
				num_threads += increase_step
			end
			return test_plans 
		end

		# get the test plan level parameters and this will be used to save to elasticsearch for futher analysis
		def get_testplan_parameters()
			variables = {}
			user_defined_variables = @doc.xpath('//jmeterTestPlan/hashTree/TestPlan/elementProp[@name="TestPlan.user_defined_variables"]/collectionProp/elementProp')
			user_defined_variables.each {|variable|
				name = variable.xpath('stringProp[@name="Argument.name"]').inner_html
				value = variable.xpath('stringProp[@name="Argument.value"]').inner_html
				variables[name] = value
			}
			return variables
		end

		# Call the JMeter to run the test plan/plans
		def JMXHelper.run_testplan(testplan)
			helper = JMXHelper.new(testplan)
			# Export the parameters related thus the JMeter can save them into the elasticsearch
			ENV["PT_num_threads"] = helper.get_num_threads()
			ENV["PT_ramp_time"] = helper.get_ramp_time()
			ENV["PT_duration"] = helper.get_duration()
			ENV["PT_loops"] = helper.get_loops()
			ENV["PT_customized_parameters"] = helper.get_testplan_parameters().to_json
			ENV["PT_test_plan"] = File.basename(testplan)
			# Call JMeter to run the performance test
			Dir.chdir(File.dirname(testplan)) do
				puts "Start to run the test plan #{testplan}"
				puts "num_threads:#{ENV["PT_num_threads"]} ramp_time:#{ENV["PT_ramp_time"]} duration:#{ENV["PT_duration"]} loops:#{ENV["PT_loops"]} testplan:#{ENV["PT_test_plan"]}"
				JMeterHelper.run_testplan(testplan)
				puts "The execution for test plan #{ENV["PT_test_plan"]} is finished."
			end
			# Remove the environment variables after execution
			ENV.delete("PT_num_threads")
			ENV.delete("PT_ramp_time")
			ENV.delete("PT_duration")
			ENV.delete("PT_loops")
			ENV.delete("PT_customized_parameters")
			ENV.delete("PT_test_plan")
		end

		# private methods

		def modify_thread_num(num)
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.num_threads"]').first().inner_html = "#{num}"
		end

		def modify_warmup_period(warm_up_period)
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.ramp_time"]').first().inner_html = "#{warm_up_period}"
		end

		def modify_duration(duration)
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.duration"]').first().inner_html = "#{duration}"
		end

		def modify_loops(loops)
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/elementProp[@name="ThreadGroup.main_controller"]/stringProp[@name="LoopController.loops"]').first().inner_html = "#{loops}"
		end

		def get_num_threads()
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.num_threads"]').first().inner_html
		end

		def get_ramp_time()
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.ramp_time"]').first().inner_html
		end

		def get_duration()
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/stringProp[@name="ThreadGroup.duration"]').first().inner_html
		end

		def get_loops()
			@doc.xpath('//jmeterTestPlan/hashTree/hashTree/ThreadGroup/elementProp[@name="ThreadGroup.main_controller"]/stringProp[@name="LoopController.loops"]').first().inner_html 
		end


	end
end

# Test code
start_num_threads = ENV["Start_Num_Threads"] == nil ? 5 : ENV["Start_Num_Threads"].to_i
increase_step = ENV["Increase_Step"] == nil ? 2 : ENV["Increase_Step"].to_i
target_num_threads = ENV["Target_Num_Threads"] == nil ? 10 : ENV["Target_Num_Threads"].to_i
test_plan_template = ENV["Test_Plan"]
test_plans_path = "#{ Time.now.strftime("%Y%m%d%H%M%S") }"
Dir.mkdir(test_plans_path, 0777)
FileUtils.mv("Test_Plan", File.join(test_plans_path, test_plan_template))
testplans = Utilities::JMXHelper.generate_testplans(File.join(test_plans_path, test_plan_template), start_num_threads, increase_step, target_num_threads)
puts "Totally #{testplans.size} test plans to run."
testplans.each do |test_plan|
	Utilities::JMXHelper.run_testplan(test_plan)
end
puts "All #{testplans.size } test plans have been finished."
# modify the loads parameters
#helper = JMXHelper.new('testplans/apt/AllProgram.jmx')
#puts helper.get_testplan_parameters
#helper.modify_loops(10)
#helper.modify_duration('1')
#helper.modify_warmup_period('1')
#helper.modify_thread_num(20)
#puts helper.generate_new_testplan('testplans/neil.jmx')










