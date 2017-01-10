module Utilities
	class JMeterHelper
		def JMeterHelper.run_testplan(testplan, timeout = 0)
			jmeter_bin = `which jmeter`
			if(jmeter_bin == "")
				#default bin folder
				jmeter_bin = '/home/nwang/servers/apache-jmeter-3.0-server/bin/jmeter'
			else
				jmeter_bin = jmeter_bin.delete("\n")
			end
			remote_servers = ''
			if(remote_servers!='')
				system("#{jmeter_bin} -n -t #{testplan} -R #{remote_servers}")
			else
				system("#{jmeter_bin} -n -t #{testplan}")
			end
		end
	end
end