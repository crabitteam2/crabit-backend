package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SimulationPreservationWindowIT {
    @Test void locksAllFortyTablesButAllowsReadsAndReleasesOnSuccessAndFailure() throws Exception {
        try(var db=new SimulationPostgresClock(); var other=db.dataSource().getConnection()) {
            String id=UUID.randomUUID().toString();
            sql(other,"INSERT INTO student(id,nickname,age) VALUES ('"+id+"','before',9)");
            var before=SimulationPreservationFingerprint.capture(db);
            String update="UPDATE student SET nickname='after' WHERE id='"+id+"'";
            var observed=SimulationPreservationWindow.read(db,before.digest(),(jdbc,locked)->{
                assertThat(locked).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid=l.relation JOIN pg_namespace n ON n.oid=c.relnamespace WHERE l.pid=pg_backend_pid() AND n.nspname='public' AND l.mode='ShareRowExclusiveLock' AND l.granted",Long.class)).isEqualTo(40);
                try {
                    sql(other,"SET lock_timeout='200ms'");
                    assertThat(scalar(other,"SELECT nickname FROM student WHERE id='"+id+"'" )).isEqualTo("before");
                    assertThatThrownBy(()->sql(other,update)).isInstanceOf(SQLException.class)
                        .satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("55P03"));
                    assertThatThrownBy(()->sql(other,"ALTER TABLE student ADD COLUMN forbidden_test text"))
                        .isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("55P03"));
                } catch(SQLException e) {throw new RuntimeException(e);}
                return locked.digest();
            });
            assertThat(observed).isEqualTo(before.digest());
            assertThat(SimulationPreservationFingerprint.capture(db)).isEqualTo(before);
            assertThatThrownBy(()->SimulationPreservationWindow.read(db,before.digest(),(jdbc,s)->{
                jdbc.update("UPDATE student SET nickname='rolled-back' WHERE id=?",UUID.fromString(id));
                return "must not escape";
            })).hasMessage("PRESERVATION_WINDOW_STATE_CHANGED");
            assertThat(SimulationPreservationFingerprint.capture(db)).isEqualTo(before);
            assertThatThrownBy(()->SimulationPreservationWindow.read(db,before.digest(),(jdbc,s)->{
                throw new IllegalArgumentException("callback failed");
            })).hasMessage("callback failed");
            sql(other,update); // Both rollback paths released the lock.
            assertThat(scalar(other,"SELECT nickname FROM student WHERE id='"+id+"'" )).isEqualTo("after");
        }
    }

    @Test void writerCommittingWhileLocksWaitIsVisibleAndStaleBackupCallbackNeverRuns() throws Exception {
        try(var db=new SimulationPostgresClock();var writer=db.dataSource().getConnection();
            var observer=db.dataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
            String id=UUID.randomUUID().toString();
            sql(writer,"INSERT INTO student(id,nickname,age) VALUES ('"+id+"','before',9)");
            var before=SimulationPreservationFingerprint.capture(db);
            writer.setAutoCommit(false);
            sql(writer,"UPDATE student SET nickname='committed-writer' WHERE id='"+id+"'");
            var called=new AtomicBoolean();
            var future=executor.submit(()->SimulationPreservationWindow.read(db,before.digest(),(jdbc,s)->{called.set(true);return s;}));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Long.parseLong(scalar(observer,"SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid=l.relation WHERE c.relname='student' AND l.mode='ShareRowExclusiveLock' AND NOT l.granted"))>0;
                    if(waiting)break;
                    Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
                writer.commit();
                assertThatThrownBy(()->future.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().hasMessage("SELECTION_BACKUP_REVISION_CONFLICT");
                assertThat(called).isFalse();
                assertThat(scalar(observer,"SELECT nickname FROM student WHERE id='"+id+"'" )).isEqualTo("committed-writer");
                var current=SimulationPreservationFingerprint.capture(db);
                var fresh=SimulationPreservationWindow.read(db,current.digest(),(jdbc,s)->s);
                assertThat(fresh).isEqualTo(current);
            } finally {writer.rollback();future.cancel(true);}
        }
    }

    @Test void lockTimeoutNeverRunsCallbackAndLeavesExistingWriterUntouched() throws Exception {
        try(var db=new SimulationPostgresClock();var writer=db.dataSource().getConnection()) {
            var before=SimulationPreservationFingerprint.capture(db);
            writer.setAutoCommit(false);sql(writer,"LOCK TABLE student IN ROW EXCLUSIVE MODE");
            var called=new AtomicBoolean();
            try {
                assertThatThrownBy(()->SimulationPreservationWindow.read(db,before.digest(),(jdbc,s)->{called.set(true);return s;}))
                    .rootCause().isInstanceOf(SQLException.class)
                    .satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("55P03"));
                assertThat(called).isFalse();
                assertThat(scalar(writer,"SELECT count(*) FROM student")).isEqualTo("0");
            } finally {writer.rollback();}
            var fresh=SimulationPreservationWindow.read(db,before.digest(),(jdbc,s)->s);
            assertThat(fresh).isEqualTo(before);
        }
    }
    private static void sql(Connection c,String text) throws SQLException {try(var s=c.createStatement()){s.execute(text);}}
    private static String scalar(Connection c,String text) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
}
