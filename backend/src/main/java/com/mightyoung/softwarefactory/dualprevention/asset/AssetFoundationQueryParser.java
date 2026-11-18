package com.mightyoung.softwarefactory.dualprevention.asset;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The structured query's body bound is independent of selector and command DTO rules. */
public final class AssetFoundationQueryParser {
    private final ObjectMapper json;
    public AssetFoundationQueryParser(ObjectMapper json){this.json=Objects.requireNonNull(json);}
    JsonNode parse(HttpServletRequest request){return AssetFoundationReadJson.body(request,json,32768);}
}
