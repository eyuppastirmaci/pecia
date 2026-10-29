package dev.eyuppastirmaci.pecia.cli;

/** Runs an action when the process is asked to stop, such as by Ctrl+C; a seam over shutdown hooks for tests. */
@FunctionalInterface
interface InterruptHandlers {

    /** Registers the action until the returned registration is closed. */
    Registration register(Runnable action);

    /**
     * Returns handlers that run in a shutdown hook, which the JVM starts on Ctrl+C (SIGINT) and SIGTERM. The JVM
     * exits as soon as every hook has returned, without waiting for the main thread, so a hook that needs the main
     * thread to finish must wait for it.
     */
    static InterruptHandlers shutdownHooks() {
        return action -> {
            Thread hook = new Thread(action, "pecia-interrupt");
            Runtime.getRuntime().addShutdownHook(hook);

            return () -> {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException shuttingDown) {
                    // The hook is already running, and the JVM exits once it returns.
                }
            };
        };
    }

    /** A registered action, removed when closed. */
    @FunctionalInterface
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
