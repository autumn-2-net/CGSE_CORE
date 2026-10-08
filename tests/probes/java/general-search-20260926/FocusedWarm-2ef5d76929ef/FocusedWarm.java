package org.cgse.core;
import java.util.*;
import static org.cgse.core.AdversarialProbe.*;
import static org.cgse.core.ContrastProbe.*;
public class FocusedWarm {
 public static void main(String[]args){Locale.setDefault(Locale.ROOT);for(var f:List.of(multi(20,2,1000,42,0),multi(20,2,1000,42,1),randomSat(24,103,0),sat(96,384,42),explicitPairs(multi(20,2,1000,42,0)),multi(48,2,1000,42,0))){
  int warm=24,measure=51;String status=null;for(int i=0;i<warm;i++)status=run(f,3000,true).result().status();double[]t=new double[measure];for(int i=0;i<measure;i++){var checked=run(f,3000,true);var r=checked.result();if(!r.status().equals(status))throw new AssertionError("inconsistent result "+r);t[i]=r.ms();if(!checked.plan().missingExact().isEmpty()){var p=checked.plan();var funded=new LinkedHashMap<String,Long>(f.stock());p.missingExact().forEach((k,v)->funded.merge(k,v.longValueExact(),Math::addExact));verify(new Fixture(f.name(),f.recipes(),funded,f.target(),f.amount(),f.dag()),summary(p.steps(),p.recipes()));}if(i==0)System.out.println("TRACE "+f.name()+" "+r.info());}Arrays.sort(t);System.out.printf("WARM %s status=%s median_ms=%.5f p90_ms=%.5f samples=%d warmup=%d%n",f.name(),status,t[measure/2],t[(int)(measure*.9)],measure,warm);}}
}
