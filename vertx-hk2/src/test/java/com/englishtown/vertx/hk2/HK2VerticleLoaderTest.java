/*
 * The MIT License (MIT)
 * Copyright © 2016 Englishtown <opensource@englishtown.com>
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

import com.englishtown.vertx.hk2.integration.CustomBinder;
import com.englishtown.vertx.hk2.integration.DependencyInjectionVerticle;
import io.vertx.core.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.logging.LogDelegate;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static com.englishtown.vertx.hk2.HK2VerticleLoader.*;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HK2VerticleLoader}
 */
@RunWith(MockitoJUnitRunner.class)
public class HK2VerticleLoaderTest {

    private ServiceLocator parent;
    private JsonObject config = new JsonObject();
    private static LogDelegate logger;

    @Mock
    private Vertx vertx;
    @Mock
    private Context context;
    @Mock
    private Future<Void> startFuture;
    @Mock
    private Future<Void> stopFuture;

    @BeforeClass
    public static void setupOnce() {
        logger = MockLogDelegateFactory.getLogDelegate();
    }

    @Before
    public void setUp() {
        MockLogDelegateFactory.reset();
        when(context.config()).thenReturn(config);
        parent = ServiceLocatorUtilities.bind(new HK2VertxBinder(vertx));
    }

    private HK2VerticleLoader doTest(String main) throws Exception {
        HK2VerticleLoader loader = create(main);

        loader.start(startFuture);
        verify(startFuture).complete();
        verify(startFuture, never()).fail(any(Throwable.class));
        verify(startFuture).complete();

        loader.stop(stopFuture);
        verify(stopFuture, never()).fail(any(Throwable.class));
        verify(stopFuture).complete();

        return loader;
    }

    private HK2VerticleLoader create(String main) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        HK2VerticleLoader loader = new HK2VerticleLoader(main, cl, parent);

        loader.init(vertx, context);
        return loader;
    }

    @Test
    public void testStart_Compiled() throws Exception {

        String main = DependencyInjectionVerticle.class.getName();
        doTest(main);

        verifyZeroInteractions(logger);

    }

    @Test
    public void testStart_Uncompiled() throws Exception {

        String main = "UncompiledDIVerticle.java";
        doTest(main);

        verifyZeroInteractions(logger);

    }

    @Test
    public void testStart_Custom_Binder() throws Exception {

        config.put(CONFIG_BOOTSTRAP_BINDER_NAME, CustomBinder.class.getName());

        String main = DependencyInjectionVerticle.class.getName();
        doTest(main);

        verifyZeroInteractions(logger);

    }

    @Test
    public void testStart_Custom_Binder_Array() throws Exception {

        config.put(CONFIG_BOOTSTRAP_BINDER_NAME, new JsonArray()
                .add(CustomBinder.class.getName())
                .add(BootstrapBinder.class.getName()));

        String main = DependencyInjectionVerticle.class.getName();
        doTest(main);

        verifyZeroInteractions(logger);

    }

    @Test
    public void testStart_Not_A_Binder() throws Exception {

        String binder = String.class.getName();
        config.put(CONFIG_BOOTSTRAP_BINDER_NAME, binder);

        String main = DependencyInjectionVerticle.class.getName();

        try {
            doTest(main);
            fail("Expected exception");
        } catch (MultiException e) {
            // Expected
        }

        verify(logger).error(eq("Class " + binder + " does not implement Binder."));

    }

    @Test
    public void testStart_Class_Not_Found_Binder() throws Exception {

        String binder = "com.englishtown.INVALID_BINDER";
        config.put(CONFIG_BOOTSTRAP_BINDER_NAME, binder);

        String main = DependencyInjectionVerticle.class.getName();

        try {
            doTest(main);
            fail("Expected exception");
        } catch (MultiException e) {
            // Expected
        }

        verify(logger).warn(eq("HK2 bootstrap binder class " + binder + " was not found.  Are you missing injection bindings?"));

    }

    @Test
    public void testStart_Fail() throws Exception {

        Verticle verticle = create(TestStartFailVerticle.class.getName());
        verticle.start(startFuture);

        verify(startFuture, never()).complete();
        verify(startFuture).fail(any(Throwable.class));

    }

    @Test
    public void testStop_Fail() throws Exception {

        Verticle verticle = create(TestStopFailVerticle.class.getName());
        verticle.stop(stopFuture);

        verify(stopFuture, never()).complete();
        verify(stopFuture).fail(any(Throwable.class));

    }

    @Test
    public void testStop_Throw() throws Exception {

        Verticle verticle = create(TestStopThrowVerticle.class.getName());
        verticle.stop(stopFuture);

        verify(stopFuture, never()).complete();
        verify(stopFuture).fail(any(Throwable.class));

    }

    private static class TestStartFailVerticle extends AbstractVerticle {
        @Override
        public void start(Future<Void> startFuture) throws Exception {
            startFuture.fail(new RuntimeException());
        }
    }

    private static class TestStopFailVerticle extends AbstractVerticle {
        @Override
        public void stop(Future<Void> stopFuture) throws Exception {
            stopFuture.fail(new RuntimeException());
        }
    }

    private static class TestStopThrowVerticle extends AbstractVerticle {
        @Override
        public void stop(Future<Void> stopFuture) throws Exception {
            throw new RuntimeException();
        }
    }
}
