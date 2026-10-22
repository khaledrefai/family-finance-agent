package ae.familyfinance.core.support;

import java.util.UUID;

/** Fixed ids from db/seed/R__demo_family.sql. */
public final class DemoFamily {
    public static final UUID FAMILY = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    public static final UUID SARA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID OMAR = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    public static final UUID LAYLA = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    public static final UUID ADAM = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    public static final String PASSWORD = "demo1234";

    private DemoFamily() {
    }
}
