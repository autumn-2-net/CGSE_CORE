package org.cgse.core;
import java.util.*;

public final class ObligationReview {
    record Key(int id) {static long hashes;public int hashCode(){hashes++;return id;}}
    public static long[] run(int width) {
        var shared=new Key(-1);var accounts=new OutputObligations<Key>(Map.of(shared,4L));
        for(int i=0;i<width;i++)accounts.dispatch("r"+i,1,Map.of(shared,3L,new Key(i),2L),i%31==0);
        for(int i=0;i<256;i++)if(accounts.inFlight(shared)!=3L*width)throw new AssertionError("setup");
        Key.hashes=0;long start=System.nanoTime(),sum=0;
        for(int i=0;i<4096;i++)sum+=accounts.inFlight(shared);
        long elapsed=System.nanoTime()-start,hashes=Key.hashes;
        if(sum!=4096L*3*width)throw new AssertionError("query");
        accounts.returned(shared,4L+width);accounts=new OutputObligations<>(accounts.snapshot());
        if(accounts.inFlight(shared)!=2L*width)throw new AssertionError("partial/reload");
        for(int i=width-1;i>=0;i--)accounts.returned(new Key(i),2);
        accounts.returned(shared,2L*width);
        if(accounts.hasFlights()||!accounts.all().isEmpty()||accounts.ambiguous())throw new AssertionError("settlement");
        return new long[]{elapsed,hashes};
    }
    static String state(OutputObligations<String> accounts) {
        var snap=accounts.snapshot();StringBuilder text=new StringBuilder().append(new TreeMap<>(accounts.external())).append(new TreeMap<>(accounts.inFlight())).append(new TreeMap<>(accounts.all())).append(accounts.ambiguous()).append(accounts.hasFlights()).append(accounts.capacityAvailable()).append(snap.nextId());
        for(String key:List.of("A","B","C","D","E","F"))text.append(accounts.inFlight(key));
        for(var f:snap.flights())text.append(f.id()).append(f.recipe()).append(f.runs()).append(new TreeMap<>(f.remaining())).append(f.ambiguous());
        return text.toString();
    }
    public static String states(int seed) {
        Random random=new Random(100072L+seed);var accounts=new OutputObligations<String>(Map.of("A",5L));StringBuilder record=new StringBuilder();
        for(int step=0;step<160;step++) {
            int action=random.nextInt(9);String key=String.valueOf((char)('A'+random.nextInt(6)));
            if(action<3) {
                Map<String,Long> output=new LinkedHashMap<>();for(int k=0;k<1+random.nextInt(4);k++)output.merge(String.valueOf((char)('A'+random.nextInt(6))),1L+random.nextInt(100),Long::sum);
                accounts.dispatch("r"+step,1+random.nextInt(20),output,random.nextInt(11)==0);
            }else if(action<5) {long n=accounts.all().getOrDefault(key,0L);accounts.returned(key,Math.min(n,random.nextInt(100)));}
            else if(action==5)accounts.addExternal(Map.of(key,1L+random.nextInt(50)));
            else if(action==6)accounts.deferExternal(key,Math.min(accounts.external(key),random.nextInt(50)));
            else if(action==7)accounts=new OutputObligations<>(accounts.snapshot());
            else if(random.nextInt(15)==0)accounts.clear();
            var snap=accounts.snapshot();Map<String,Long> oracle=new HashMap<>();snap.flights().forEach(f->f.remaining().forEach((k,v)->oracle.merge(k,v,Long::sum)));
            for(String k:List.of("A","B","C","D","E","F"))if(accounts.inFlight(k)!=oracle.getOrDefault(k,0L))throw new AssertionError("Stale index");
            if(!accounts.inFlight().equals(oracle))throw new AssertionError("All outputs differ");
            record.append(state(accounts)).append('\n');
        }
        return record.toString();
    }
    public static void main(String[] args) {
        for(int seed=0;seed<1000;seed++)states(seed);
        System.out.println("PASS 160000 dispatch/return/external/reload/clear operations, per-flight independent accounting");
        var accounts=new OutputObligations<String>(Map.of());accounts.dispatch("large",1,Map.of("A",Long.MAX_VALUE),false);
        if(accounts.inFlight("A")!=Long.MAX_VALUE)throw new AssertionError("Long total");
        accounts.returned("A",Long.MAX_VALUE);if(accounts.hasFlights())throw new AssertionError("Long return");
        System.out.println("PASS Long.MAX_VALUE exact return");
    }
}
