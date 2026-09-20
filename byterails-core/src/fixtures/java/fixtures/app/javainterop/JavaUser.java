package fixtures.app.javainterop;

import java.util.List;
import java.util.function.Supplier;

import fixtures.lib.config.JavaConstants;
import fixtures.lib.jooq.DSLContext;
import fixtures.lib.persistence.EntityManager;
import fixtures.lib.web.RestTemplate;

/** Java constructs whose references live in unusual places of the class file. */
public class JavaUser {

    /** EntityManager appears only in the field's generic signature. */
    private List<EntityManager> managers;

    /** A static final int is inlined: nothing in the class file names JavaConstants. */
    public static final int LIMIT = JavaConstants.LIMIT;

    /** RestTemplate appears only in the bootstrap arguments of the invokedynamic call site. */
    public Supplier<?> supplier() {
        return RestTemplate::new;
    }

    /** String concatenation goes through java.lang.invoke.StringConcatFactory. */
    public String concat(String a) {
        return "value=" + a;
    }

    public enum Color { RED, GREEN }

    /** A switch over an enum creates a synthetic JavaUser$1 class holding the switch map. */
    public int ordinalOf(Color color) {
        switch (color) {
            case RED:
                return 1;
            default:
                return 2;
        }
    }

    /** DSLContext is a record component. */
    public record Point(int x, DSLContext ctx) {
    }

    public sealed interface Shape permits Circle {
    }

    public record Circle(int radius) implements Shape {
    }
}
