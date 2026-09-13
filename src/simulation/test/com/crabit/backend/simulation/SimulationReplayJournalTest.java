package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulationReplayJournalTest {
    @TempDir Path temp;
    @Test void indexesSealedExecutionBytesWithoutAllocatingAnotherFileOrOverwritingARecord() throws Exception {
        var journal=new SimulationReplayJournal(temp.resolve("replay"),"sha256:"+"a".repeat(64));
        Path source=journal.root().resolve("execution.bin");byte[] bytes={0,13,10,(byte)255};
        Files.write(source,bytes);
        journal.rawFile("raw/response.bin","event-1","RESPONSE",source);
        Path raw=journal.root().resolve("raw/response.bin");
        assertThat(Files.isSameFile(source,raw)).isTrue();
        assertThat(Files.readAllBytes(raw)).isEqualTo(bytes);
        assertThatThrownBy(()->journal.rawFile("raw/response.bin","event-2","RESPONSE",source)).hasMessage("REPLAY_RAW_DUPLICATE");
        assertThat(Files.readAllBytes(raw)).isEqualTo(bytes);
    }
    @Test void neverLinksAnExternalFileOrASymlinkIntoEvidence() throws Exception {
        var journal=new SimulationReplayJournal(temp.resolve("replay"),"sha256:"+"a".repeat(64));
        Path outside=temp.resolve("outside.bin");Files.write(outside,new byte[]{1});
        assertThatThrownBy(()->journal.rawFile("raw/response.bin","event-1","RESPONSE",outside)).hasMessage("REPLAY_SOURCE_FILE");
        Path link=journal.root().resolve("link");Files.createSymbolicLink(link,temp);
        assertThatThrownBy(()->journal.rawFile("raw/response.bin","event-1","RESPONSE",link.resolve("outside.bin"))).hasMessage("REPLAY_SOURCE_FILE");
        assertThat(Files.exists(journal.root().resolve("raw/response.bin"))).isFalse();
        assertThat(Files.readAllBytes(outside)).isEqualTo(new byte[]{1});
    }
}
