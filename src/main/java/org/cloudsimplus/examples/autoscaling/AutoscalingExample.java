package org.cloudsimplus.examples.autoscaling;

import org.cloudsimplus.autoscaling.HorizontalVmScalingSimple;
import org.cloudsimplus.resources.Processor;
import org.cloudsimplus.resources.Ram;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.autoscaling.VerticalVmScalingSimple;
import org.cloudsimplus.autoscaling.VerticalVmScaling;
import org.cloudsimplus.autoscaling.resources.ResourceScalingInstantaneous;
import org.cloudsimplus.autoscaling.resources.ResourceScalingGradual;
import org.cloudsimplus.autoscaling.resources.ResourceScaling;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.vms.VmSimple;

import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

public class AutoscalingExample {

    private static final int HOSTS = 3;
    private static final int HOST_PES = 16;
    private static final int INITIAL_VMS = 2;

    private final CloudSimPlus simulation = new CloudSimPlus();
    private final DatacenterBrokerSimple broker;
    private final List<VmSimple> vmList = new ArrayList<>();
    private final List<HostSimple> hostList = new ArrayList<>();
    private double lastStatsTime = -Double.MAX_VALUE;

    // HTTP client to call local ML prediction API
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String modelEndpoint = "http://127.0.0.1:5000/predict";

    public static void main(String[] args) {
        new AutoscalingExample();
    }

    public AutoscalingExample() {
        DatacenterSimple dc = createDatacenter();
        dc.setSchedulingInterval(5);

        broker = new DatacenterBrokerSimple(simulation);

        createInitialVMs();
        broker.submitVmList(new ArrayList<>(vmList));
        broker.submitCloudletList(createCloudlets());

        // Run autoscaling decisions every 5 seconds
        simulation.addOnClockTickListener(this::predictiveAutoscalingDecision);

        System.out.println("Starting simulation...");
        simulation.start();

        new CloudletsTableBuilder(broker.getCloudletFinishedList()).build();
    }

    /*--------------------------------------------
     *          CREATE DATACENTER + HOSTS
     ---------------------------------------------*/
    private DatacenterSimple createDatacenter() {
        for (int i = 0; i < HOSTS; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < HOST_PES; p++) peList.add(new PeSimple(1000));

                HostSimple host = new HostSimple(32000L, 10000L, 1_000_000L, peList);
                host.setBwProvisioner(new org.cloudsimplus.provisioners.ResourceProvisionerSimple());
                host.setRamProvisioner(new org.cloudsimplus.provisioners.ResourceProvisionerSimple());
                host.setVmScheduler(new VmSchedulerTimeShared());
                hostList.add(host);
        }
        return new DatacenterSimple(simulation, hostList);
    }

    /*--------------------------------------------
     *            INITIAL VM CREATION
     ---------------------------------------------*/
    private void createInitialVMs() {
        for (int i = 0; i < INITIAL_VMS; i++) {
            VmSimple vm = createScalableVm(i);
            vmList.add(vm);
        }
    }

    private VmSimple createScalableVm(int id) {
        VmSimple vm = new VmSimple(1000, 2);
        vm.setRam(2048).setBw(2000).setSize(10000);
        vm.setCloudletScheduler(new CloudletSchedulerTimeShared());

        /* ---------- Horizontal Scaling ---------- */
        var horizontalScaling = new HorizontalVmScalingSimple();
        horizontalScaling.setVmSupplier(() -> createScalableVm(vmList.size()));
        horizontalScaling.setOverloadPredicate(v -> v.getCpuPercentUtilization() > 0.75);
        vm.setHorizontalScaling(horizontalScaling);

        /* ---------- Vertical Scaling - CPU ---------- */
          VerticalVmScalingSimple cpuScaling = new VerticalVmScalingSimple(Processor.class, 0.75);
          // Disable downward vertical scaling (avoid illegal PE reductions) by using 0.0 lower threshold
          cpuScaling.setLowerThresholdFunction(v -> 0.0)
              .setUpperThresholdFunction(v -> 0.75)
              .setResourceScaling(new CappedResourceScaling(new ResourceScalingInstantaneous()));
        vm.setPeVerticalScaling(cpuScaling);

        /* ---------- Vertical Scaling - RAM ---------- */
          VerticalVmScalingSimple ramScaling = new VerticalVmScalingSimple(Ram.class, 0.75);
          // Disable downward vertical scaling for RAM (prevent repeated failed downscale attempts)
          ramScaling.setLowerThresholdFunction(v -> 0.0)
              .setUpperThresholdFunction(v -> 0.75)
              .setResourceScaling(new ResourceScalingGradual());
          // The scaling factor is a fraction of the current capacity (e.g. 0.25 = +25%).
          // Previously using 512 multiplied the VM's capacity (MB) producing huge deltas.
          ramScaling.setScalingFactor(0.25);
        vm.setRamVerticalScaling(ramScaling);

        return vm;
    }

    /*--------------------------------------------
     *        CREATE CLOUDLETS
     ---------------------------------------------*/
    private List<CloudletSimple> createCloudlets() {
        List<CloudletSimple> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            CloudletSimple c = new CloudletSimple(5000, 2);
            c.setFileSize(100).setOutputSize(100);
            c.setUtilizationModelCpu(new org.cloudsimplus.utilizationmodels.UtilizationModelFull());
            // stagger cloudlet submission so autoscaling can create new VMs that receive work
            c.setSubmissionDelay(i * 0.5);
            list.add(c);
        }
        return list;
    }

    /*--------------------------------------------
     *  MAIN PREDICTIVE AUTOSCALING DECISION LOOP
     ---------------------------------------------*/
    private void predictiveAutoscalingDecision(EventInfo info) {
        double time = info.getTime();
        double interval = 5.0;
        // clock ticks are floating-point and may be offset (e.g. 5.10). Print once
        // for each interval by checking the elapsed time since the last print.
        if (time - lastStatsTime < interval - 1e-3) return;
        lastStatsTime = time;

        System.out.println("\n----- Predictive Autoscaling at time " + String.format("%.2f", time) + " -----");

        // Runtime summary: finished cloudlets, VM counts, per-VM utilization
        int finishedCloudlets = broker.getCloudletFinishedList().size();
        long allocatedVms = vmList.stream().filter(v -> v.getHost() != null && !v.isFinished()).count();
        System.out.printf("Summary: finishedCloudlets=%d, totalVMs=%d, allocatedVMs=%d\n",
            finishedCloudlets, vmList.size(), allocatedVms);

        for (VmSimple vmS : vmList) {
            double cpuUtil = vmS.getCpuPercentUtilization();
            double ramUtil = vmS.getRam().getPercentUtilization();
            String hostId = vmS.getHost() != null ? String.valueOf(vmS.getHost().getId()) : "-";
            System.out.printf(" VM %d: CPU=%.2f%% RAM=%.2f%% Host=%s\n",
                vmS.getId(), cpuUtil * 100.0, ramUtil * 100.0, hostId);
        }

        // For each VM, pull predicted ML values
        for (VmSimple vm : vmList) {
            Map<String, Double> predicted = getPredictedMetrics(vm, time);

            double cpuPred = predicted.get("cpu");
            double ramPred = predicted.get("ram");
            double bwPred = predicted.get("bw");

            System.out.printf("VM %d → Predicted CPU=%.2f RAM=%.2f BW=%.2f\n",
                    vm.getId(), cpuPred, ramPred, bwPred);

            /* =========================================================
             *                AUTOSCALING DECISIONS
             * ========================================================= */

            /* ----------- HORIZONTAL SCALING -------------- */
            if (cpuPred > 0.80) {
                System.out.println(" → CPU high → triggering horizontal autoscaling");
                // create and submit a new VM to simulate horizontal scaling
                VmSimple newVm = createScalableVm(vmList.size());
                vmList.add(newVm);
                broker.submitVmList(Collections.singletonList(newVm));
            }

            /* ----------- VERTICAL SCALING (CPU) ---------- */
            if (cpuPred > 0.75) {
                System.out.println(" → Scaling UP CPU vertically (adding a PE)");
                // approximate vertical CPU scaling by increasing available PEs
                vm.setFreePesNumber(vm.getFreePesNumber() + 1);
            }

            /* ----------- VERTICAL SCALING (RAM) ---------- */
            if (ramPred > 0.75) {
                System.out.println(" → Scaling UP RAM vertically");
                long currentRam = vm.getRam().getCapacity();
                vm.setRam(currentRam + 512);
            }
        }
    }

    /*--------------------------------------------
     *       ML PREDICTION FUNCTION (calls local REST API)
     ---------------------------------------------*/
    private Map<String, Double> getPredictedMetrics(VmSimple vm, double time) {
        Map<String, Double> pred = new HashMap<>();

        // Build features expected by the model:
        // 1 num_tasks, 2 avg_task_size_MB, 3 vm_type, 4 num_users, 5 time_of_day, 6 priority_level
        double[] features = buildFeaturesForVm(vm, time);

        try {
            String jsonResponse = callModelApi(features);
            // Parse response for CPU_utilization, Memory_usage, Network_bw_MBps
            double cpu = extractDoubleFromJson(jsonResponse, "CPU_utilization");
            double mem = extractDoubleFromJson(jsonResponse, "Memory_usage");
            double bw  = extractDoubleFromJson(jsonResponse, "Network_bw_MBps");

            // Sanity clamp: model returns absolute values for IO/bw; we normalize ram and bw to [0,1]
            cpu = clamp(cpu, 0.0, 1.0);
            // Memory_usage likely in fraction already (sample 0.46). If model returned MBs adjust heuristically.
            mem = clamp(mem, 0.0, 1.0);
            // Network bandwidth: sample returned 14.55 MBps in absolute units; convert to a relative 0-1 scale
            // We assume 100 MBps as a rough upper bound for normalization here.
            bw = clamp(bw / 100.0, 0.0, 1.0);

            pred.put("cpu", cpu);
            pred.put("ram", mem);
            pred.put("bw", bw);
            return pred;
        } catch (Exception e) {
            // If API call fails for any reason fall back to the previous simulated heuristic
            System.err.println("Warning: model API call failed, using fallback simulated predictions: " + e.getMessage());
            return simulatedPredictions(vm, time);
        }
    }

    private double[] buildFeaturesForVm(VmSimple vm, double time) {
        int numTasks = broker.getCloudletFinishedList().size();
        double avgTaskSizeMB = 100.0; // our cloudlets use fileSize=100 as a proxy
        int vmType = (int) ((vm.getId() % 3) + 1); // types 1..3
        int numUsers = 50 * (vm.getId() + 1);
        int timeOfDay = (int) (time % 24);
        int priorityLevel = 1 + (int) (vm.getId() % 5);

        return new double[]{numTasks, avgTaskSizeMB, vmType, numUsers, timeOfDay, priorityLevel};
    }

    private String callModelApi(double[] features) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"features\": [");
        for (int i = 0; i < features.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(features[i]);
        }
        sb.append("]}");
        String requestBody = sb.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(modelEndpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Model API returned status " + response.statusCode());
        }
        return response.body();
    }

    // Very small and lenient JSON number extractor for flat responses like the sample
    private double extractDoubleFromJson(String json, String key) {
        String needle = '"' + key + '"';
        int idx = json.indexOf(needle);
        if (idx == -1) idx = json.indexOf(key);
        if (idx == -1) throw new IllegalArgumentException("Key not found in JSON: " + key);
        int colon = json.indexOf(':', idx);
        if (colon == -1) throw new IllegalArgumentException("Malformed JSON near key: " + key);
        int start = colon + 1;
        // skip spaces
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        // allow negative, digits, dot, exponent
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') end++;
            else break;
        }
        String numStr = json.substring(start, end).trim();
        return Double.parseDouble(numStr);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Map<String, Double> simulatedPredictions(VmSimple vm, double time) {
        Map<String, Double> pred = new HashMap<>();
        double base = (time + vm.getId());
        double cpu = 0.3 + 0.25 * Math.sin(base / 2.0);
        double ram = 0.4 + 0.2 * Math.sin(base / 3.0 + 1.0);
        double bw  = 0.2 + 0.15 * Math.cos(base / 2.5 + 2.0);
        double noise = ((vm.getId() % 3) - 1) * 0.01; // -0.01, 0.0 or +0.01
        cpu = Math.max(0.0, Math.min(1.0, cpu + noise));
        ram = Math.max(0.0, Math.min(1.0, ram + noise / 2));
        bw  = Math.max(0.0, Math.min(1.0, bw  - noise / 3));
        pred.put("cpu", cpu);
        pred.put("ram", ram);
        pred.put("bw", bw);
        return pred;
    }

    /**
     * A small ResourceScaling wrapper that prevents Processor scaling requests
     * that exceed the host's available PEs. If no host is assigned or no free
     * PEs exist, it returns 0 so no scaling is attempted.
     */
    private static class CappedResourceScaling implements ResourceScaling {
        private final ResourceScaling delegate;

        CappedResourceScaling(ResourceScaling delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public double getResourceAmountToScale(org.cloudsimplus.autoscaling.VerticalVmScaling vmScaling) {
            double amount = delegate.getResourceAmountToScale(vmScaling);
            // Only cap Processor scaling (PEs). For other resources, return as-is.
            if (vmScaling.getResource() instanceof Processor) {
                org.cloudsimplus.vms.Vm vm = vmScaling.getVm();
                Host host = vm.getHost();
                if (host == null) return 0.0;
                int freePes = host.getFreePesNumber();
                return Math.min(amount, (double) freePes);
            }
            return amount;
        }
    }
}