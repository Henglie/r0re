package com.fyr.r0re.dispatcher;

public final class WorkerAdapters {
    private WorkerAdapters() {
    }

    public static WorkerAdapter forType(String type, DispatchConfig.ContainerConfig containerConfig, String promptGroup, String server) {
        return forType(type, containerConfig, promptGroup, server, null);
    }

    public static WorkerAdapter forType(String type, DispatchConfig.ContainerConfig containerConfig, String promptGroup, String server, DockerCli docker) {
        if (type == null || type.equals("mock")) {
            return new MockWorkerAdapter();
        }
        if (docker == null) {
            return new ExternalCliWorkerAdapter(containerConfig, promptGroup, type, server);
        }
        return new ExternalCliWorkerAdapter(containerConfig, docker, new PromptFactory(promptGroup), WorkerDrivers.forType(type), server);
    }
}
