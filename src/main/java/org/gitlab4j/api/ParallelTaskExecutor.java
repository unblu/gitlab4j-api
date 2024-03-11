package org.gitlab4j.api;

import java.util.List;
import java.util.concurrent.Callable;

public interface ParallelTaskExecutor {
    public <T> List<T> execute(List<Callable<T>> tasks) throws Exception;
}
