package com.quest.horizonconfig;

import java.util.ArrayList;
import java.util.List;

/** MobileConfig value metadata discovered from Horizon on the current headset. */
final class Catalog {
    static final class Value {
        final String name;
        final int type;
        final String baseline;
        final boolean inDeviceConfig;
        final boolean experiment;
        final String declaredBy;
        final String readBy;

        Value(String name, int type, String baseline, boolean inDeviceConfig, boolean experiment,
              String declaredBy, String readBy) {
            this.name = name;
            this.type = type;
            this.baseline = baseline;
            this.inDeviceConfig = inDeviceConfig;
            this.experiment = experiment;
            this.declaredBy = declaredBy;
            this.readBy = readBy;
        }

        String namespace() {
            int colon = name.indexOf(':');
            return colon < 0 ? "" : name.substring(0, colon);
        }

        boolean referenced() {
            return !readBy.isEmpty();
        }
    }

    static List<Value> load() {
        return new ArrayList<>();
    }

    static String typeName(int type) {
        return switch (type) {
            case Overrides.TYPE_BOOLEAN -> "bool";
            case Overrides.TYPE_LONG -> "long";
            case Overrides.TYPE_DOUBLE -> "double";
            default -> "string";
        };
    }

    private Catalog() {
    }
}
