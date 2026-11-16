package nav.dash;

/** ELM327 replies to "010C" (engine RPM = (A*256+B)/4) in the forms real adapters send. */
public class ObdParseTest {
    static int ok, bad;
    static void eq(String name, int got, int want) { if (got == want) ok++; else { bad++; System.out.println("FAIL " + name + ": got " + got + " want " + want); } }
    public static void main(String[] a) {
        eq("spaces", ObdParse.rpm("41 0C 1A F8 \r\r"), 1726);
        eq("no spaces", ObdParse.rpm("410C1AF8"), 1726);
        eq("echo on", ObdParse.rpm("010C\r41 0C 0B B8\r"), 750);
        eq("headers on (CAN)", ObdParse.rpm("7E8 04 41 0C 2E E0\r"), 3000);
        eq("searching first", ObdParse.rpm("SEARCHING...\r41 0C 7D 00\r"), 8000);
        eq("idle zero", ObdParse.rpm("41 0C 00 00"), 0);
        eq("lowercase", ObdParse.rpm("41 0c 1a f8"), 1726);
        eq("no data", ObdParse.rpm("NO DATA\r"), -1);
        eq("unable", ObdParse.rpm("UNABLE TO CONNECT\r"), -1);
        eq("truncated", ObdParse.rpm("41 0C 1A"), -1);
        eq("other pid", ObdParse.rpm("41 0D 32"), -1);
        System.out.println(ok + "/" + (ok + bad) + " passed"); if (bad > 0) System.exit(1);
    }
}
