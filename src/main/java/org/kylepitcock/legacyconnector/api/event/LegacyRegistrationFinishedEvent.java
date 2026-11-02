package org.kylepitcock.legacyconnector.api.event;

import net.minecraftforge.eventbus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** Phase {@code REGISTERED}: every legacy mod ran preInit, init and postInit; all their blocks, items and entities are registered. */
public class LegacyRegistrationFinishedEvent extends Event implements IModBusEvent {}
