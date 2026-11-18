package com.mightyoung.softwarefactory.applicationregistry.definition;

import java.util.List;
import java.util.Set;

/** Implemented by the business owner beside its real handlers; no reverse module import. */
public interface EntityDefinitionProvider {
    List<EntityDefinition> definitions();
    Set<EntityDefinition.CommandBinding> commandBindings();
    Set<EntityDefinition.ReferenceBinding> referenceBindings();
}
