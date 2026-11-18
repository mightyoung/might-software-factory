package com.mightyoung.softwarefactory.dualprevention.asset;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded structured read input. It never resolves identity, authority or SQL names. */
final class AssetFoundationReadJson {
    private AssetFoundationReadJson() { }

    static JsonNode body(HttpServletRequest request,ObjectMapper json,int maximumBytes) {
        if(request.getQueryString()!=null)throw invalid();
        var contentTypes=Collections.list(request.getHeaders("Content-Type"));
        if(contentTypes.size()!=1||!contentTypes.getFirst().matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\"UTF-8\"))?[ \\t]*"))
            throw new AssetFoundationFailure(415);
        try {
            byte[] bytes=request.getInputStream().readNBytes(maximumBytes+1);
            if(bytes.length>maximumBytes)throw new AssetFoundationFailure(413);
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return tree(text,json);
        }catch(java.io.IOException|tools.jackson.core.JacksonException failure){throw invalid();}
    }

    static JsonNode tree(String text,ObjectMapper json) {
        if(text==null||text.startsWith("\uFEFF"))throw invalid();
        try(var parser=json.createParser(text)) {
            var objects=new ArrayDeque<Set<String>>();
            int depth=0,roots=0;
            JsonToken token;
            while((token=parser.nextToken())!=null) {
                if(token==JsonToken.START_OBJECT||token==JsonToken.START_ARRAY) {
                    if(depth==0&&++roots>1)throw invalid();
                    if(++depth>8)throw invalid();
                    objects.push(token==JsonToken.START_OBJECT?new HashSet<>():Collections.emptySet());
                }else if(token==JsonToken.END_OBJECT||token==JsonToken.END_ARRAY) {
                    if(objects.isEmpty())throw invalid();
                    objects.pop();depth--;
                }else if(token==JsonToken.PROPERTY_NAME) {
                    if(objects.isEmpty()||!objects.peek().add(parser.currentName()))throw invalid();
                    unicode(parser.currentName());
                }else if(token==JsonToken.VALUE_STRING){if(depth==0)throw invalid();unicode(parser.getString());}
                else if(token==JsonToken.VALUE_NUMBER_FLOAT||token==JsonToken.VALUE_NUMBER_INT) {
                    if(depth==0||!parser.getString().matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))throw invalid();
                }
                else if(depth==0)throw invalid();
            }
            JsonNode result=json.reader(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text);
            if(result==null||!result.isObject())throw invalid();
            return result;
        }catch(tools.jackson.core.JacksonException|UnsupportedOperationException failure){throw invalid();}
    }

    static void fields(JsonNode node,Set<String> required,Set<String> optional) {
        if(node==null||!node.isObject())throw invalid();
        Set<String> found=new HashSet<>();
        node.properties().forEach(entry->{
            if(!required.contains(entry.getKey())&&!optional.contains(entry.getKey()))throw invalid();
            found.add(entry.getKey());
        });
        if(!found.containsAll(required))throw invalid();
    }
    static String text(JsonNode node) {
        if(node==null||!node.isString())throw invalid();
        String value=node.stringValue();unicode(value);return value;
    }
    static int integer(JsonNode node,int minimum,int maximum) {
        if(node==null||!node.isIntegralNumber()||!node.canConvertToInt())throw invalid();
        int value=node.intValue();
        if(value<minimum||value>maximum)throw invalid();
        return value;
    }
    static UUID uuid(String value) { return AssetFoundationRequestParser.uuid(value); }
    static Instant time(String value) {
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw invalid();
        try{return AssetContracts.instant(OffsetDateTime.parse(value).toInstant());}
        catch(java.time.DateTimeException error){throw invalid();}
    }
    static void unicode(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c==0||Character.isLowSurrogate(c))throw invalid();
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();
            }
        }
    }
    static AssetFoundationFailure invalid(){return new AssetFoundationFailure(400);}
}
