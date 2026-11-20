package legacyconnector.v1_20_1.net.minecraftforge.eventbus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Forge's runtime marker for event types whose Event.Result value has meaning. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Event$HasResult {
}
