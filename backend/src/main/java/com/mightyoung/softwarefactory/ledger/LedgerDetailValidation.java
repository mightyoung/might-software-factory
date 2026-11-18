package com.mightyoung.softwarefactory.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;

/** Bounded ordered detail validation, performed before any aggregate writes. */
public final class LedgerDetailValidation {
    private LedgerDetailValidation() { }
    public static <T> void validate(String field,List<T> rows,int maximum,BiConsumer<Integer,T> referenceCheck) {
        if(rows==null||rows.isEmpty()||rows.size()>maximum)throw new LedgerInputException(field,"invalid_size");
        var unique=new HashSet<T>();
        for(int i=0;i<rows.size();i++) {
            T value=rows.get(i);
            if(value==null||!unique.add(value))throw new LedgerInputException(field+"["+i+"]","duplicate_or_invalid_reference");
            referenceCheck.accept(i,value);
        }
    }
}
