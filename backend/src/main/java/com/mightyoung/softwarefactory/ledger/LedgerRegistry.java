package com.mightyoung.softwarefactory.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class LedgerRegistry {
    private final Map<String,LedgerBinding> bindings;
    public LedgerRegistry(List<LedgerBinding> registered) {
        Map<String,LedgerBinding> entries=new LinkedHashMap<>(); var validator=new LedgerBindingValidator();
        for(var binding:registered) {
            validator.validate(binding);
            if(entries.put(key(binding.definition().appKey(),binding.definition().entityKey()),binding)!=null) throw new IllegalArgumentException("Duplicate app/entity ledger binding");
        }
        bindings=Map.copyOf(entries);
    }
    public LedgerBinding require(String appKey,String entityKey) {
        var binding=bindings.get(key(appKey,entityKey));
        if(binding==null) throw new LedgerException(404,"RESOURCE_NOT_VISIBLE");
        return binding;
    }
    private static String key(String app,String entity) { return app+"/"+entity; }
}
