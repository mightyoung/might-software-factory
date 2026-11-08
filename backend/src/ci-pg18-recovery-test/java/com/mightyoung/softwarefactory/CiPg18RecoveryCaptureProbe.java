package com.mightyoung.softwarefactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Fixed capture source only; counts actual physical transaction effects without changing role. */
final class CiPg18RecoveryCaptureProbe extends AbstractDataSource {
    private final DataSource source;
    final AtomicInteger borrows=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger();
    CiPg18RecoveryCaptureProbe(DataSource source){this.source=source;}
    @Override public Connection getConnection()throws SQLException {
        Connection raw=source.getConnection();borrows.incrementAndGet();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("toString"))return "RecoveryCaptureConnection[REDACTED]";
            try{Object result=method.invoke(raw,args);
                if(method.getName().equals("commit"))commits.incrementAndGet();
                if(method.getName().equals("rollback"))rollbacks.incrementAndGet();
                return result;
            }catch(InvocationTargetException failure){throw failure.getTargetException();}
        });
    }
    @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Fixed capture identity","08004");}
    @Override public String toString(){return "RecoveryCaptureProbe[REDACTED]";}
}
