package com.mightyoung.softwarefactory.applicationregistry.navigation;

import java.util.List;

/** Code-owned application contribution; granted canonical capabilities are projected, never inferred from definitions. */
public record ApplicationContribution(String appKey, String label, List<Node> nodes) {
    public ApplicationContribution {
        if (appKey==null || !appKey.matches("[a-z][a-z0-9_]{0,63}") || label==null || label.isBlank()) throw new IllegalArgumentException("Invalid application contribution");
        nodes=List.copyOf(nodes);
        for (Node node:nodes) {
            if (!node.path().equals("apps/"+appKey+"/entities/"+node.entityKey())) throw new IllegalArgumentException("Contribution route must match application/entity");
        }
    }
    /** Any granted permission can open the entry; endpoint actions retain their distinct authorization. */
    public record Node(String id, String label, String path, String entityKey, List<String> permissions) {
        public Node {
            if (id==null || id.isBlank() || label==null || label.isBlank() || path==null || entityKey==null
                    || !entityKey.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid contribution node");
            permissions=List.copyOf(permissions);
            if (permissions.isEmpty() || permissions.stream().distinct().count()!=permissions.size()
                    || permissions.stream().anyMatch(p -> !p.matches(java.util.regex.Pattern.quote(entityKey)+"\\.(read|create|update)"))) {
                throw new IllegalArgumentException("Invalid contribution capabilities");
            }
        }
    }
}
