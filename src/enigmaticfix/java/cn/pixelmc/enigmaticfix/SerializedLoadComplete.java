package cn.pixelmc.enigmaticfix;

import java.lang.reflect.InvocationTargetException;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Execute the three original handlers through Forge's serial deferred queue. */
public final class SerializedLoadComplete {
    private static final Logger LOGGER = LoggerFactory.getLogger("PixelMC Enigmatic Startup Fix");
    private static final ThreadLocal<Object> RUNNING = new ThreadLocal<>();

    public static boolean defer(Object mod, FMLLoadCompleteEvent event) {
        if (RUNNING.get() == mod) return false;
        String name = mod.getClass().getName();
        LOGGER.info("Deferring original load-complete handler: {}", name);
        event.enqueueWork(() -> {
            Object previous = RUNNING.get();
            RUNNING.set(mod);
            LOGGER.info("Serial handler BEGIN: {} on {}", name, Thread.currentThread().getName());
            try {
                mod.getClass().getMethod("onLoadComplete", FMLLoadCompleteEvent.class).invoke(mod, event);
                LOGGER.info("Serial handler END: {}", name);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Original load-complete handler failed: " + name, cause);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to invoke original load-complete handler: " + name, exception);
            } finally {
                if (previous == null) RUNNING.remove(); else RUNNING.set(previous);
            }
        });
        return true;
    }

    private SerializedLoadComplete() {}
}
