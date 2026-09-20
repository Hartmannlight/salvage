package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import de.chrisliebaer.salvage.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafetyTest {
    @Test void customCodesAreFailuresAndZeroIsAccepted() {
        var policy = ExitCodeBehaviour.fromString("1,3-5,7-9");
        for (var value : new long[]{1,3,4,5,7,8,9}) assertFalse(policy.check(value));
        for (var value : new long[]{0,2,6,10}) assertTrue(policy.check(value));
        assertFalse(ExitCodeBehaviour.fromString("stop").check(1));
        assertFalse(ExitCodeBehaviour.fromString("fail").check(1));
    }

    record Fixture(SalvageContainer container, AtomicBoolean running, StartContainerCmd start) {}
    private Fixture fixture(DockerClient docker, String id) {
        var running = new AtomicBoolean(true);
        var inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getState().getRunning()).thenAnswer(i -> running.get());
        when(inspect.getState().getPaused()).thenReturn(false);
        when(inspect.getState().getRestarting()).thenReturn(false);
        var inspectCmd = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(id)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        var stop = mock(StopContainerCmd.class);
        when(docker.stopContainerCmd(id)).thenReturn(stop);
        when(stop.exec()).thenAnswer(i -> { running.set(false); return null; });
        var start = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(id)).thenReturn(start);
        when(start.exec()).thenAnswer(i -> {
            assertFalse(Thread.currentThread().isInterrupted(), "rollback uses a clean interrupt state");
            running.set(true); return null;
        });
        return new Fixture(new SalvageContainer(id, id, Optional.empty(), List.of(),
            SalvageContainer.ContainerAction.STOP, Optional.empty(), Optional.empty(),
            new ExitCodeBehaviour.FailIfNonZero()), running, start);
    }

    @Test void rollbackRestoresEveryContainer() throws Exception {
        var docker = mock(DockerClient.class);
        var a = fixture(docker,"a"); var b = fixture(docker,"b");
        var tx = new StateTransaction(docker);
        tx.prepare(a.container()); tx.prepare(b.container());
        assertFalse(a.running().get()); assertFalse(b.running().get());
        tx.close();
        assertTrue(a.running().get()); assertTrue(b.running().get());
        tx.close();
        verify(a.start(),times(1)).exec(); verify(b.start(),times(1)).exec();
    }

    @Test void rollbackContinuesAfterFailureAndCanRetry() throws Exception {
        var docker = mock(DockerClient.class);
        var a=fixture(docker,"a"); var b=fixture(docker,"b");
        when(a.start().exec()).thenThrow(new IllegalStateException("temporary Docker failure"));
        var tx=new StateTransaction(docker); tx.prepare(a.container()); tx.prepare(b.container());
        var error=assertThrows(IllegalStateException.class,tx::close);
        assertEquals(1,error.getSuppressed().length);
        assertTrue(b.running().get()); assertFalse(a.running().get());
        doAnswer(i -> {a.running().set(true);return null;}).when(a.start()).exec();
        tx.close(); assertTrue(a.running().get());
        verify(b.start(),times(1)).exec();
    }

    @Test void rollbackClearsInterruptUntilAllDockerCallsFinish() throws Exception {
        var docker=mock(DockerClient.class); var a=fixture(docker,"a"); var b=fixture(docker,"b");
        var tx=new StateTransaction(docker);tx.prepare(a.container());tx.prepare(b.container());
        Thread.currentThread().interrupt();
        try {tx.close();assertTrue(a.running().get());assertTrue(b.running().get());assertTrue(Thread.currentThread().isInterrupted());}
        finally {Thread.interrupted();}
    }

    @Test void lostStopResponseStillRestoresTheApplication() throws Exception {
        var docker=mock(DockerClient.class); var a=fixture(docker,"a");
        var stop=docker.stopContainerCmd("a");
        doAnswer(i -> {a.running().set(false);throw new IllegalStateException("lost response");})
            .when(stop).exec();
        var tx=new StateTransaction(docker);
        assertThrows(IllegalStateException.class,() -> tx.prepare(a.container()));
        assertFalse(a.running().get());
        tx.close();
        assertTrue(a.running().get());
    }
}
