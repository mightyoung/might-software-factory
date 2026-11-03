package com.mightyoung.softwarefactory.applicationregistry.reference;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceQueryContractTest {
    @Test
    void countsUnicodeCodePointsAndDefaultsLimit() {
        assertThat(new ReferenceQueryContract.Request("😀".repeat(100), null, null).limit()).isEqualTo(20);
        assertThatThrownBy(() -> new ReferenceQueryContract.Request("😀".repeat(101), null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rejectsOutOfBoundsAndNulQueries() {
        for (int limit : List.of(0, 51)) assertThatThrownBy(() -> new ReferenceQueryContract.Request("", null, limit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReferenceQueryContract.Request("a\0b", null, 20)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void pageCopiesItsItemsAndRequiresActualLastPosition() {
        UUID id = UUID.randomUUID(); var item = new ReferenceQueryContract.Item(id, "O1", "Organization");
        var original = new ArrayList<>(List.of(item));
        var page = new ReferenceQueryContract.Page(original, id); original.clear();
        assertThat(page.items()).containsExactly(item);
        assertThatThrownBy(() -> new ReferenceQueryContract.Page(List.of(item), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReferenceQueryContract.Page(List.of(item, item), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReferenceQueryContract.Page(List.of(item), null).nextAfterId()).isNull();
    }
}
