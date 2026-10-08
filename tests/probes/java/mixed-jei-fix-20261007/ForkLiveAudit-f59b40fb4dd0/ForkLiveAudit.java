package org.cgse.core;

public final class ForkLiveAudit {
    public static void main(String[] args) throws Exception {
        var input=FrontierForkAudit.read(args[0]);
        for(int mode=0;mode<5;mode++) FrontierForkAudit.fork(input,128L<<20,mode);
        for(long bytes:new long[]{1024,16384,262144,1048576,4194304}) FrontierForkAudit.fork(input,bytes,1);
        FrontierForkAudit.ok(FrontierForkAudit.forks>=5 && FrontierForkAudit.cancelled==1,"live forks not exercised");
        System.out.println("live_checks="+FrontierForkAudit.checks+"; forks="+FrontierForkAudit.forks+"; cancellations="+FrontierForkAudit.cancelled+"; declines="+FrontierForkAudit.declined);
    }
}
