import java.util.*;

/** Offline guard checks; database behavior is verified separately against the real isolated target. */
class bootstrap_test {
    static int checks;
    public static void main(String[] args) {
        for (String host : List.of("127.0.0.1", "localhost", "192.168.1.2")) {
            bootstrap.checkTarget("jdbc:postgresql://"+host+":5432/msf_d0_acceptance_test", "msf_d0_acceptance_test", "isolated-d0-only:msf_d0_acceptance_test"); checks++;
        }
        for (String db : List.of("might_software_factory_dev", "might_software_factory_dev_20261115", "postgres", "msf_d0_test", "msf_d0_acceptance_test;drop", "msf_d0_ACCEPT_test")) {
            rejects(() -> bootstrap.checkTarget("jdbc:postgresql://localhost:5432/"+db, db, "isolated-d0-only:"+db));
        }
        for (String url : List.of("jdbc:postgresql://example.org:5432/msf_d0_acceptance_test", "jdbc:postgresql://localhost:5432/other", "jdbc:postgresql://localhost:5432/msf_d0_acceptance_test?user=postgres", "jdbc:postgresql://postgres:secret@localhost:5432/msf_d0_acceptance_test", "jdbc:postgresql:msf_d0_acceptance_test")) {
            rejects(() -> bootstrap.checkTarget(url, "msf_d0_acceptance_test", "isolated-d0-only:msf_d0_acceptance_test"));
        }
        rejects(() -> bootstrap.checkTarget("jdbc:postgresql://localhost:5432/msf_d0_acceptance_test", "msf_d0_acceptance_test", "yes"));
        bootstrap.database="msf_d0_acceptance_test"; bootstrap.tenantCode="d0seed";
        var first=bootstrap.id("reporter/account");
        if (!first.equals(bootstrap.id("reporter/account")) || first.equals(bootstrap.id("person/account"))) throw new AssertionError("Stable distinct identity required"); checks++;
        bootstrap.database="msf_d0_other_test";
        if(first.equals(bootstrap.id("reporter/account"))) throw new AssertionError("Database namespaces overlap"); checks++;
        if(bootstrap.permissions("admin").contains("finding.verify") || bootstrap.permissions("reporter").contains("platform.permission.manage")) throw new AssertionError("Role separation required"); checks++;
        System.out.println("D0_BOOTSTRAP_GUARDS_OK checks="+checks);
    }
    static void rejects(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Unsafe target accepted");
    }
}
