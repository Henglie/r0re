package com.fyr.r0re.dispatcher;

import com.fyr.r0re.protocol.Models.Intent;
import com.fyr.r0re.protocol.Models.ProjectDetail;
import java.util.List;

public class MockWorkerAdapter implements WorkerAdapter {
    @Override
    public WorkerResult bootstrap(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        return new WorkerResult.Fact("mock bootstrap fact for " + project.project().title());
    }

    @Override
    public WorkerResult reason(ProjectDetail project, DispatchConfig.WorkerConfig worker, DispatchConfig.ReasonTask taskConfig, TaskCancellation cancellation) {
        long producedFacts = project.facts().stream().filter(f -> !f.id().equals("origin") && !f.id().equals("goal")).count();
        if (producedFacts >= 2) {
            List<String> sources = project.facts().stream()
                    .map(com.fyr.r0re.protocol.Models.Fact::id)
                    .filter(id -> !id.equals("goal"))
                    .toList();
            return new WorkerResult.Complete("mock project completion after " + producedFacts + " findings", sources);
        }
        return WorkerResult.Intents.fromDescriptions(List.of("mock explore intent " + (producedFacts + 1)));
    }

    @Override
    public WorkerResult explore(ProjectDetail project, Intent intent, DispatchConfig.WorkerConfig worker, DispatchConfig.TaskTimeout taskConfig, TaskCancellation cancellation) {
        return new WorkerResult.Fact("mock explored " + intent.id() + ": " + intent.description());
    }
}
