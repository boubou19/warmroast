/*
 * WarmRoast
 * Copyright (C) 2013 Albert Pham <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.warmroast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.beust.jcommander.JCommander;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

public class WarmRoast extends TimerTask {

    private static final String SEPARATOR = 
            "------------------------------------------------------------------------";
    
    private final int interval;
    private final VirtualMachine vm;
    private final Timer timer = new Timer("Roast Pan", true);
    private final McpMapping mapping = new McpMapping();
    private final SortedMap<String, StackNode> nodes = new TreeMap<>();
    private JMXConnector connector;
    private MBeanServerConnection mbsc;
    private ThreadMXBean threadBean;
    private String filterThread;
    private long endTime = -1;
    
    public WarmRoast(VirtualMachine vm, int interval) {
        this.vm = vm;
        this.interval = interval;
    }
    
    public Map<String, StackNode> getData() {
        return nodes;
    }
    
    private StackNode getNode(String name) {
        StackNode node = nodes.get(name);
        if (node == null) {
            node = new StackNode(name);
            nodes.put(name, node);
        }
        return node;
    }
    
    public McpMapping getMapping() {
        return mapping;
    }
    
    public String getFilterThread() {
        return filterThread;
    }

    public void setFilterThread(String filterThread) {
        this.filterThread = filterThread;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long l) {
        this.endTime = l;
    }

    public void connect() 
            throws IOException, AgentLoadException, AgentInitializationException {
        // Load the agent
        String connectorAddr = vm.getAgentProperties().getProperty(
                "com.sun.management.jmxremote.localConnectorAddress");
        if (connectorAddr == null) {
            String agent = vm.getSystemProperties().getProperty("java.home")
                    + File.separator + "lib" + File.separator
                    + "management-agent.jar";
            vm.loadAgent(agent);
            connectorAddr = vm.getAgentProperties().getProperty(
                    "com.sun.management.jmxremote.localConnectorAddress");
        }

        // Connect
        JMXServiceURL serviceURL = new JMXServiceURL(connectorAddr);
        connector = JMXConnectorFactory.connect(serviceURL);
        mbsc = connector.getMBeanServerConnection();
        try {
            threadBean = getThreadMXBean();
        } catch (MalformedObjectNameException e) {
            throw new IOException("Bad MX bean name", e);
        }
    }

    private ThreadMXBean getThreadMXBean() 
            throws IOException, MalformedObjectNameException {
        ObjectName objName = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
        Set<ObjectName> mbeans = mbsc.queryNames(objName, null);
        for (ObjectName name : mbeans) {
            return ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, name.toString(), ThreadMXBean.class);
        }
        throw new IOException("No thread MX bean found");
    }

    @Override
    public synchronized void run() {
        if (endTime >= 0) {
            if (endTime <= System.currentTimeMillis()) {
                cancel();
                System.err.println("Sampling has stopped.");
                return;
            }
        }
        //try in case of the monitored vm crashes/stops
        try {
            ThreadInfo[] threadDumps = threadBean.dumpAllThreads(false, false);
            for (ThreadInfo threadInfo : threadDumps) {
                String threadName = threadInfo.getThreadName();
                StackTraceElement[] stack = threadInfo.getStackTrace();

                if (threadName == null || stack == null) {
                    continue;
                }

                if (filterThread != null && !filterThread.equals(threadName)) {
                    continue;
                }

                StackNode node = getNode(threadName);
                node.log(stack, interval);
            }
        }
        catch (Exception e){
            System.err.println("There was an error while monitoring the jvm");
            System.err.println("Warmroast is stopping");
            cancel();
            System.exit(3);
        }
    }

    public void start(InetSocketAddress address) throws Exception {
        timer.scheduleAtFixedRate(this, interval, interval);
        
        Server server = new Server(address);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new DataViewServlet(this)), "/stack");

        ResourceHandler resources = new ResourceHandler();
        String filesDir = WarmRoast.class.getResource("/www").toExternalForm();
        resources.setResourceBase(filesDir);
        resources.setDirectoriesListed(true);
        resources.setWelcomeFiles(new String[]{ "index.html" });
 
        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(resources);
        server.setHandler(handlers);

        server.start();
        server.join();
    }

    public static void main(String[] args) throws AgentLoadException {
        RoastOptions opt = new RoastOptions();
        JCommander jc = new JCommander(opt, args);
        jc.setProgramName("warmroast");
        
        if (opt.help) {
            jc.usage();
            System.exit(0);
        }

        System.err.println(SEPARATOR);
        System.err.println("WarmRoast");
        System.err.println("http://github.com/sk89q/warmroast");
        System.err.println(SEPARATOR);
        System.err.println("");

        //remove the jvm launching warmroast
        List<VirtualMachineDescriptor> virtualMachineDescriptors = VirtualMachine.list().stream()
                .filter(vmd -> !vmd.displayName().contains("com.sk89q.warmroast.WarmRoast"))
                .collect(Collectors.toList());;
        if (virtualMachineDescriptors.size() == 0){
            System.err.println("There is no jvm to sample, launch one first.");
            System.exit(1);
        }
        VirtualMachine vm = null;
        
        if (opt.pid != null) {
            try {
                vm = VirtualMachine.attach(String.valueOf(opt.pid));
                System.err.println("Attaching to PID " + opt.pid + "...");
            } catch (AttachNotSupportedException | IOException e) {
                System.err.println("Failed to attach VM by PID " + opt.pid);
                e.printStackTrace();
                System.exit(1);
            }
        } else if (opt.vmName != null) {
            for (VirtualMachineDescriptor desc : virtualMachineDescriptors) {
                if (desc.displayName().contains(opt.vmName)) {
                    try {
                        vm = VirtualMachine.attach(desc);
                        System.err.println("Attaching to '" + desc.displayName() + "'...");
                        
                        break;
                    } catch (AttachNotSupportedException | IOException e) {
                        System.err.println("Failed to attach VM by name '" + opt.vmName + "'");
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
            }
        }
        if (opt.preciseMode && opt.vmName != null){
            System.err.println("Specified VM name was not found and precise mode is enabled. Exiting.");
            System.exit(0);
        }
        else if (vm == null) {
            System.err.println("Choose a VM:");
            
            Collections.sort(virtualMachineDescriptors, new Comparator<VirtualMachineDescriptor>() {
                @Override
                public int compare(VirtualMachineDescriptor o1,
                        VirtualMachineDescriptor o2) {
                    return o1.displayName().compareTo(o2.displayName());
                }
            });
            
            // Print list of VMs
            int i = 1;
            for (VirtualMachineDescriptor desc : virtualMachineDescriptors) {
                System.err.println("[" + (i++) + "] " + desc.displayName());
            }
            
            // Ask for choice
            System.err.println("");
            System.err.print("Enter choice #: ");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String s;
            try {
                s = reader.readLine();
            } catch (IOException e) {
                return;
            }
            
            // Get the VM
            try {
                int choice = Integer.parseInt(s) - 1;
                if (choice < 0 || choice >= virtualMachineDescriptors.size()) {
                    System.err.println("");
                    System.err.println("Given choice is out of range.");
                    System.exit(1);
                }
                vm = VirtualMachine.attach(virtualMachineDescriptors.get(choice));
            } catch (NumberFormatException e) {
                System.err.println("");
                System.err.println("That's not a number. Bye.");
                System.exit(1);
            } catch (AttachNotSupportedException | IOException e) {
                System.err.println("");
                System.err.println("Failed to attach VM");
                e.printStackTrace();
                System.exit(1);
            }
        }
        
        InetSocketAddress address = new InetSocketAddress(opt.bindAddress, opt.port);

        WarmRoast roast = new WarmRoast(vm, opt.interval);
        if (opt.mappingsDir != null) {
            File dir = new File(opt.mappingsDir);
            File joined = new File(dir, "joined.srg");
            File methods = new File(dir, "methods.csv");
            try {
                roast.getMapping().read(joined, methods);
            } catch (IOException e) {
                System.err.println(
                        "Failed to read the mappings files (joined.srg, methods.csv) " +
                        "from " + dir.getAbsolutePath() + ": " + e.getMessage());
                System.exit(2);
            }
        }

        System.err.println(SEPARATOR);
        
        roast.setFilterThread(opt.threadName);
        
        if (opt.timeout != null && opt.timeout > 0) {
            roast.setEndTime(System.currentTimeMillis() + opt.timeout * 1000);
            System.err.println("Sampling set to stop in " + opt.timeout + " seconds.");
        }

        System.err.println("Starting a server on " + address.toString() + "...");
        System.err.println("Once the server starts (shortly), visit the URL in your browser.");
        System.err.println("Note: The longer you wait before using the output of that " +
        		"webpage, the more accurate the results will be.");
        
        try {
            roast.connect();
            roast.start(address);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(3);
        }
    }
}
