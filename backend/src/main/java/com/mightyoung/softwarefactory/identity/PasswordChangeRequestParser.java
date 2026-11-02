package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded strict UTF-8 JSON parser: exactly two unique string fields and no trailing value. */
@Component
public final class PasswordChangeRequestParser {
    private final ObjectMapper json;
    public PasswordChangeRequestParser(ObjectMapper json) { this.json=json; }
    public PasswordChangeRequest parse(HttpServletRequest request) {
        byte[] bytes=new byte[4097]; char[] decodedCharacters=new char[4097]; char[] current=null, next=null;
        try {
            if(request.getContentType()==null)throw invalid();
            MediaType media=MediaType.parseMediaType(request.getContentType());
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(media)||media.isWildcardType()||media.isWildcardSubtype()
                    ||(media.getCharset()!=null&&!StandardCharsets.UTF_8.equals(media.getCharset())))throw invalid();
            int length=request.getInputStream().readNBytes(bytes,0,bytes.length);
            if(length>4096)throw invalid();
            // Parse only the strict UTF-8 result, never bytes that Jackson could charset-autodetect.
            // Own the output before decoding so a malformed suffix cannot leave an unwipeable prefix.
            var characters=java.nio.CharBuffer.wrap(decodedCharacters);
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var decoded=decoder.decode(ByteBuffer.wrap(bytes,0,length),characters,true);
            if(decoded.isError())decoded.throwException();
            if(decoded.isOverflow())throw invalid();
            var flushed=decoder.flush(characters);
            if(flushed.isError())flushed.throwException();
            if(flushed.isOverflow())throw invalid();
            try(var parser=json.createParser(decodedCharacters,0,characters.position())) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw invalid();
                int seen=0;
                while(parser.nextToken()!=JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid();
                    int field=switch(parser.currentName()){case "currentPassword"->1;case "newPassword"->2;default->throw invalid();};
                    if((seen&field)!=0||parser.nextToken()!=JsonToken.VALUE_STRING)throw invalid();
                    seen|=field;
                    char[] source=parser.getStringCharacters();int offset=parser.getStringOffset(),size=parser.getStringLength();
                    try { if(size<1||size>72)throw invalid();
                        char[] value=Arrays.copyOfRange(source,offset,offset+size);
                        if(field==1)current=value;else next=value;
                    } finally { Arrays.fill(source,offset,offset+size,'\0'); }
                }
                if(seen!=3||parser.nextToken()!=null)throw invalid();
            }
            return new PasswordChangeRequest(current,next);
        } catch(PasswordChangeException failure){throw failure;}
        catch(Exception failure){throw invalid();}
        finally { Arrays.fill(bytes,(byte)0);Arrays.fill(decodedCharacters,'\0');if(current!=null)Arrays.fill(current,'\0');if(next!=null)Arrays.fill(next,'\0'); }
    }
    static PasswordChangeException invalid(){return new PasswordChangeException(PasswordChangeFailureReason.INVALID_NEW_PASSWORD,400);}
}
