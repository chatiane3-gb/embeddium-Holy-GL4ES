package org.embeddedt.embeddium.api.options.structure;

import org.embeddedt.embeddium.api.options.control.Control;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.options.OptionIdentifier;

import java.util.Collection;

public interface Option<T> {
    OptionIdentifier<T> getId();

    Component getName();

    Component getTooltip();

    OptionImpact getImpact();

    Control<T> getControl();

    T getValue();

    void setValue(T value);

    void reset();

    OptionStorage<?> getStorage();

    boolean isAvailable();

    boolean hasChanged();

    void applyChanges();

    Collection<OptionFlag> getFlags();
}
