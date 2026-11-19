package com.mightyoung.softwarefactory.ledger;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded UTF-8 transport with duplicate-key, unknown-property and scalar-coercion rejection. */
@Component
public final class LedgerHttpParser {
    private static final int MAX_BYTES=65_536;
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public <T>T read(HttpServletRequest request,Class<T> type) {
        try {
            var media=MediaType.parseMediaType(request.getContentType()==null?"":request.getContentType());
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(media) || media.getCharset()!=null && !media.getCharset().equals(StandardCharsets.UTF_8))throw invalid();
            byte[] bytes=request.getInputStream().readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw new LedgerException(413,"INVALID_REQUEST");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(var parser=JSON.createParser(text)) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw invalid();
                var objects=new ArrayDeque<Set<String>>();objects.push(new HashSet<>());int depth=1,tokens=1;
                for(JsonToken token=parser.nextToken();token!=null;token=parser.nextToken()) {
                    if(++tokens>20_000)throw invalid();
                    if(token==JsonToken.START_OBJECT) {if(++depth>8)throw invalid();objects.push(new HashSet<>());}
                    else if(token==JsonToken.START_ARRAY) {if(++depth>8)throw invalid();}
                    else if(token==JsonToken.END_OBJECT) {depth--;objects.pop();}
                    else if(token==JsonToken.END_ARRAY) depth--;
                    else if(token==JsonToken.PROPERTY_NAME && !objects.peek().add(parser.currentName()))throw invalid();
                    if(depth==0 && parser.nextToken()!=null)throw invalid();
                }
                if(depth!=0)throw invalid();
            }
            return JSON.readValue(text,type);
        }catch(LedgerException rejected){throw rejected;}
        catch(Exception invalid){throw invalid();}
    }
    public static UUID uuid(String value,String field) {
        try {var id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id;}
        catch(RuntimeException invalid){throw new LedgerInputException(field,"invalid_uuid");}
    }
    private static LedgerInputException invalid(){return new LedgerInputException("request","invalid_json");}
}
