package org.erlide.engine;

import java.io.File;

import org.erlide.util.services.ExtensionUtils;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class ModelActivator implements BundleActivator {

    private static IErlangServiceFactory engine;

    @Override
    public void start(final BundleContext context) throws Exception {
        System.out.println("Activate MODEL");

        engine = ExtensionUtils.getSingletonExtension(
                "org.erlide.model.api.serviceFactory",
                IErlangServiceFactory.class);

        cleanupStateDir();
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
    }

    public static IErlangServiceFactory getErlangEngine() {
        return engine;
    }

    private void cleanupStateDir() {
        final String ndir = engine.getStateDir();
        final File fdir = new File(ndir);
        for (final File f : fdir.listFiles()) {
            if (f.isFile()) {
                f.delete();
            }
        }
    }
}
