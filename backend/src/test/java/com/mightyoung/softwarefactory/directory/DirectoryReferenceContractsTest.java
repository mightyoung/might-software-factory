package com.mightyoung.softwarefactory.directory;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.mightyoung.softwarefactory.directory.DirectoryReferenceContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectoryReferenceContractsTest {
    @Test void acceptsSupplementaryCodePointsAndBoundsPages() {
        assertThat(new Query("😀".repeat(100), null, null).limit()).isEqualTo(20);
        assertThatThrownBy(() -> new Query("😀".repeat(101), null, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("x\0y", null, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("", null, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Query("", null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesDuplicateCandidatesAndForgedNextPosition() {
        UUID id = UUID.randomUUID();
        Item item = new Item(id, "A", "Name");
        assertThatThrownBy(() -> new Page(List.of(item, item), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page(List.of(item), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Page(List.of(), id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Page(List.of(item), id).nextAfterId()).isEqualTo(id);
    }
    @Test void refusesUnownedConnectionBeforeBorrowingOrQuerying() {
        javax.sql.DataSource source = mock(javax.sql.DataSource.class);
        var repository = new JooqDirectoryReferenceReadRepository(source);
        assertThatThrownBy(() -> repository.findOrganizationAt(UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class).hasMessage("Directory reference boundary is unavailable");
        verifyNoInteractions(source);
    }
}
