/*
 * The MIT License (MIT)
 * Copyright © 2013 Englishtown <opensource@englishtown.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the “Software”), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.englishtown.vertx.hk2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.impl.verticle.CompilingClassLoader;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.utilities.Binder;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;

import java.util.ArrayList;
import java.util.List;

/**
 * HK2 Verticle to lazy load the real verticle with DI
 */
public class HK2VerticleLoader extends AbstractVerticle {

    private final Logger logger = LoggerFactory.getLogger(HK2VerticleLoader.class);

    private final String verticleName;
    private ClassLoader classLoader;
    private Verticle realVerticle;
    private ServiceLocator locator;

    public static final String CONFIG_BOOTSTRAP_BINDER_NAME = "hk2_binder";
    public static final String BOOTSTRAP_BINDER_NAME = "com.englishtown.vertx.hk2.BootstrapBinder";

    public HK2VerticleLoader(String verticleName, ClassLoader classLoader) {
        this.verticleName = verticleName;
        this.classLoader = classLoader;
    }

    /**
     * Override this method to signify that start is complete sometime _after_ the start() method has returned
     * This is useful if your verticle deploys other verticles or modules and you don't want this verticle to
     * be considered started until the other modules and verticles have been started.
     *
     * @param startedResult When you are happy your verticle is started set the result
     * @throws Exception
     */
    @Override
    public void start(Future<Void> startedResult) throws Exception {

        // Create the real verticle
        try {
            realVerticle = createRealVerticle();
        } catch (Exception e) {
            startedResult.fail(e);
            return;
        }

        // Init and start the real verticle
        realVerticle.init(vertx, context);
        realVerticle.start(startedResult);

    }

    /**
     * Vert.x calls the stop method when the verticle is undeployed.
     * Put any cleanup code for your verticle in here
     *
     * @throws Exception
     */
    @Override
    public void stop(Future<Void> stopFuture) throws Exception {

        this.classLoader = null;

        // Destroy the service locator
        ServiceLocatorFactory.getInstance().destroy(locator);
        locator = null;

        // Stop the real verticle
        if (realVerticle != null) {
            realVerticle.stop(stopFuture);
            realVerticle = null;
        }
    }

    public String getVerticleName() {
        return verticleName;
    }

    public Verticle createRealVerticle() throws Exception {
        String className = verticleName;
        Class<?> clazz;

        if (className.endsWith(".java")) {
            CompilingClassLoader compilingLoader = new CompilingClassLoader(classLoader, className);
            className = compilingLoader.resolveMainClassName();
            clazz = compilingLoader.loadClass(className);
        } else {
            clazz = classLoader.loadClass(className);
        }
        Verticle verticle = createRealVerticle(clazz);
        return verticle;
    }

    private Verticle createRealVerticle(Class<?> clazz) throws Exception {

        JsonObject config = context.config();
        Object field = config.getValue(CONFIG_BOOTSTRAP_BINDER_NAME);
        JsonArray bootstrapNames;
        List<Binder> bootstraps = new ArrayList<>();

        if (field instanceof JsonArray) {
            bootstrapNames = (JsonArray) field;
        } else {
            bootstrapNames = new JsonArray().add((field == null ? BOOTSTRAP_BINDER_NAME : field));
        }

        for (int i = 0; i < bootstrapNames.size(); i++) {
            String bootstrapName = bootstrapNames.getString(i);
            try {
                Class bootstrapClass = classLoader.loadClass(bootstrapName);
                Object obj = bootstrapClass.newInstance();

                if (obj instanceof Binder) {
                    bootstraps.add((Binder) obj);
                } else {
                    logger.error("Class " + bootstrapName
                            + " does not implement Binder.");
                }
            } catch (ClassNotFoundException e) {
                logger.error("HK2 bootstrap binder class " + bootstrapName
                        + " was not found.  Are you missing injection bindings?");
            }
        }

        // Each verticle factory will have it's own service locator instance
        // Passing a null name will not cache the locator in the factory
        locator = ServiceLocatorFactory.getInstance().create(null);

        bootstraps.add(0, new HK2VertxBinder(vertx));
        ServiceLocatorUtilities.bind(locator, bootstraps.toArray(new Binder[]{}));

        return (Verticle) locator.createAndInitialize(clazz);
    }

}