package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Strict bounded transport parser. It runs only inside the service's lazy admitted supplier. */
@Component
public final class IdentityLifecycleRequestParser {
    private static final int MAX_BYTES=8192;
    private final ObjectMapper json;
    public IdentityLifecycleRequestParser(ObjectMapper json){this.json=json;}

    public InvitationCommand invitation(HttpServletRequest request) {
        UUID key=key(request);
        try(var body=read(request,Set.of("kind","loginKey","displayName","targetAccountId","destination"))) {
            Purpose purpose=Purpose.valueOf(body.string("kind"));
            if(purpose==Purpose.NEW_ACCOUNT) {
                body.exact("kind","loginKey","displayName","destination");
                return new InvitationCommand(key,purpose,null,body.string("loginKey"),body.string("displayName"),body.destination);
            }
            if(purpose!=Purpose.EXISTING_ACCOUNT_MEMBERSHIP)throw invalid();
            body.exact("kind","targetAccountId","destination");
            return new InvitationCommand(key,purpose,uuid(body.string("targetAccountId")),null,null,body.destination);
        } catch(IdentityLifecycleException failure){throw failure;}
        catch(RuntimeException malformed){throw invalid();}
    }
    public MutationCommand mutation(HttpServletRequest request,String invitationId) {
        UUID id=uuid(invitationId),key=key(request);
        try(var body=read(request,Set.of("expectedVersion"))) {
            body.exact("expectedVersion");return new MutationCommand(id,key,body.expectedVersion);
        }
    }
    public String resetRequest(HttpServletRequest request) {
        try(var body=read(request,Set.of("loginKey"))) {
            body.exact("loginKey");String login=body.string("loginKey");text(login,200);return login;
        }
    }
    public ConsumeCommand resetConsume(HttpServletRequest request) {
        return consume(request,"resetId",true);
    }
    public ConsumeCommand invitationConsume(HttpServletRequest request) {
        return consume(request,"invitationId",false);
    }
    private ConsumeCommand consume(HttpServletRequest request,String idField,boolean passwordRequired) {
        UUID key=key(request);
        try(var body=read(request,Set.of(idField,"token","newPassword"))) {
            if(passwordRequired||body.seen.contains("newPassword"))body.exact(idField,"token","newPassword");
            else body.exact(idField,"token");
            UUID id=uuid(body.string(idField));
            return new ConsumeCommand(id,key,body.takeToken(),body.password);
        }
    }
    private Body read(HttpServletRequest request,Set<String> allowed) {
        byte[] bytes=new byte[MAX_BYTES+1];char[] characters=new char[MAX_BYTES+1];Body body=new Body();boolean success=false;
        try {
            if(request.getQueryString()!=null||request.getContentType()==null)throw invalid();
            var media=MediaType.parseMediaType(request.getContentType());
            if(!MediaType.APPLICATION_JSON.isCompatibleWith(media)||media.isWildcardType()||media.isWildcardSubtype()
                    ||media.getCharset()!=null&&!StandardCharsets.UTF_8.equals(media.getCharset())
                    ||media.getParameters().keySet().stream().anyMatch(name->!"charset".equalsIgnoreCase(name)))throw invalid();
            var encodings=Collections.list(request.getHeaders("Content-Encoding"));
            if(!encodings.isEmpty()&&(encodings.size()!=1||!"identity".equalsIgnoreCase(encodings.getFirst())))throw invalid();
            if(request.getContentLengthLong()>MAX_BYTES)throw invalid();
            int count=request.getInputStream().readNBytes(bytes,0,bytes.length);if(count>MAX_BYTES)throw invalid();
            var output=CharBuffer.wrap(characters);
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var result=decoder.decode(ByteBuffer.wrap(bytes,0,count),output,true);if(result.isError())result.throwException();if(result.isOverflow())throw invalid();
            result=decoder.flush(output);if(result.isError())result.throwException();if(result.isOverflow())throw invalid();
            try(var parser=json.createParser(characters,0,output.position())) {
                if(parser.nextToken()!=JsonToken.START_OBJECT)throw invalid();
                while(parser.nextToken()!=JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid();
                    String field=parser.currentName();if(!allowed.contains(field)||!body.seen.add(field))throw invalid();
                    JsonToken token=parser.nextToken();
                    switch(field) {
                        case "destination" -> {if(token!=JsonToken.START_OBJECT)throw invalid();body.destination=destination(parser);}
                        case "expectedVersion" -> {if(token!=JsonToken.VALUE_NUMBER_INT)throw invalid();body.expectedVersion=parser.getLongValue();positive(body.expectedVersion);}
                        case "newPassword" -> {if(token!=JsonToken.VALUE_STRING)throw invalid();body.password=secretCharacters(parser,72);if(!validPassword(body.password))throw invalid();}
                        case "token" -> {
                            if(token!=JsonToken.VALUE_STRING)throw invalid();char[] secret=secretCharacters(parser,43);
                            try{body.token=OneUseSecret.parse(new String(secret));}finally{Arrays.fill(secret,'\0');}
                        }
                        default -> {if(token!=JsonToken.VALUE_STRING||parser.getStringLength()>320)throw invalid();body.strings.put(field,parser.getString());}
                    }
                }
                if(parser.nextToken()!=null)throw invalid();
            }
            success=true;return body;
        } catch(IdentityLifecycleException failure){throw failure;}
        catch(Exception malformed){throw invalid();}
        finally {Arrays.fill(bytes,(byte)0);Arrays.fill(characters,'\0');if(!success)body.close();}
    }
    private static Destination destination(JsonParser parser)throws Exception {
        Set<String> seen=new HashSet<>();String channel=null,value=null;
        while(parser.nextToken()!=JsonToken.END_OBJECT) {
            if(parser.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid();String field=parser.currentName();
            if(!Set.of("channel","value").contains(field)||!seen.add(field)||parser.nextToken()!=JsonToken.VALUE_STRING||parser.getStringLength()>320)throw invalid();
            if("channel".equals(field))channel=parser.getString();else value=parser.getString();
        }
        if(seen.size()!=2)throw invalid();return new Destination(channel,value);
    }
    private static char[] secretCharacters(JsonParser parser,int maximum)throws Exception {
        char[] source=parser.getStringCharacters();int offset=parser.getStringOffset(),length=parser.getStringLength();
        try {if(length<1||length>maximum)throw invalid();return Arrays.copyOfRange(source,offset,offset+length);}
        finally {Arrays.fill(source,offset,offset+length,'\0');}
    }
    private static UUID key(HttpServletRequest request) {
        var values=Collections.list(request.getHeaders("Idempotency-Key"));if(values.size()!=1)throw invalid();return uuid(values.getFirst());
    }
    private static UUID uuid(String value) {
        try {UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}
        catch(RuntimeException malformed){throw invalid();}
    }
    private static final class Body implements AutoCloseable {
        final Set<String> seen=new HashSet<>();final Map<String,String> strings=new HashMap<>();
        Destination destination;long expectedVersion;char[] password;OneUseSecret token;
        String string(String field){String value=strings.get(field);if(value==null)throw invalid();return value;}
        void exact(String... fields){if(!seen.equals(Set.of(fields)))throw invalid();}
        OneUseSecret takeToken(){if(token==null)throw invalid();OneUseSecret result=token;token=null;return result;}
        @Override public void close(){if(token!=null)token.close();if(password!=null)Arrays.fill(password,'\0');strings.clear();}
        @Override public String toString(){return "LifecycleBody[REDACTED]";}
    }
}
