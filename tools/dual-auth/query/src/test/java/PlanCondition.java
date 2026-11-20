import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;

/** Restricted compiler for the policies in this integration probe. */
final class PlanCondition {
    static Condition from(JsonNode filter) {
        return switch (filter.required("kind").asString()) {
            case "KIND_ALWAYS_ALLOWED" -> DSL.trueCondition();
            case "KIND_ALWAYS_DENIED" -> DSL.falseCondition();
            case "KIND_CONDITIONAL" -> expression(filter.required("condition"));
            default -> throw new IllegalArgumentException("Unsupported filter: " + filter);
        };
    }

    private static Condition expression(JsonNode node) {
        JsonNode expr = node.required("expression");
        String op = expr.required("operator").asString();
        JsonNode args = expr.required("operands");
        if (!args.isArray() || args.isEmpty()) throw new IllegalArgumentException("Missing operands");
        if (op.equals("and") || op.equals("or")) {
            List<Condition> parts = new ArrayList<>();
            for (JsonNode arg : args) parts.add(expression(arg));
            return op.equals("and") ? DSL.and(parts) : DSL.or(parts);
        }
        if (args.size() != 2) throw new IllegalArgumentException("Invalid operands: " + expr);
        Field<String> left = scalar(args.get(0));
        return switch (op) {
            case "eq" -> left.eq(scalar(args.get(1)));
            case "in" -> {
                JsonNode values = args.get(1).required("value");
                if (!values.isArray()) throw new IllegalArgumentException("Expected string list");
                List<String> strings = new ArrayList<>();
                for (JsonNode value : values) strings.add(text(value));
                yield left.in(strings);
            }
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    private static Field<String> scalar(JsonNode node) {
        if (node.has("value")) return DSL.val(text(node.get("value")));
        String variable = text(node.required("variable"));
        if (variable.equals("request.resource.id") || variable.equals("request.resource.attr.record_id"))
            return DSL.field(DSL.name("id"), String.class);
        String prefix = "request.resource.attr.";
        if (variable.startsWith(prefix)) {
            String column = variable.substring(prefix.length());
            if (Set.of("id","tenant","owner","department").contains(column))
                return DSL.field(DSL.name(column), String.class);
        }
        throw new IllegalArgumentException("Unsupported variable: " + variable);
    }

    private static String text(JsonNode n) {
        if (!n.isString()) throw new IllegalArgumentException("Expected string: " + n);
        return n.asString();
    }
}
