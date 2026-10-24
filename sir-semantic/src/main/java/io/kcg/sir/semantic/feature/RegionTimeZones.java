package io.kcg.sir.semantic.feature;

import java.time.ZoneId;

/** Region identifiers provided by the running tzdb, plus the unambiguous UTC identifier. */
public final class RegionTimeZones {
    private RegionTimeZones() {}

    /** Absence is governed independently by the field's mandatory/Optional contract. */
    public static boolean accepts(String value) {
        return value == null || value.equals("UTC")
                || value.contains("/") && ZoneId.getAvailableZoneIds().contains(value);
    }
}
