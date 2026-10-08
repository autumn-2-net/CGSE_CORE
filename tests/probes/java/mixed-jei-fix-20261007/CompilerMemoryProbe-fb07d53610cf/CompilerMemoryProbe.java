package org.cgse.core;
import java.nio.file.*;import java.util.*;
public final class CompilerMemoryProbe {
 public static void main(String[] args)throws Exception{
   Path area=Path.of(args[0]);var manual=MixedSweep.read(area.resolve("manual.json"));var extra=MixedSweep.read(area.resolve("reduced-extra.json"));var compiler=MixedSweep.compiler(manual,extra,"extra-first",0);
   String target="{\"#c\":\"ae2:f\",id:\"gtceu:miracle\"}";
   var budget=new PlanningBudget(0,20_000_000,128L<<20,()->false,System::nanoTime);int count=0;String status="complete";
   try{for(;count<256;count++){try(var work=compiler.begin(target,Map.of(),Set.of(),budget)){while(!work.step()){}if(work.result().recipes().size()<1000)throw new AssertionError();}}}catch(PlanningBudget.Exhausted exhausted){status=exhausted.limit().name();}
   System.out.println("compiled="+count+" status="+status+" work="+budget.nodes()+" peak="+budget.peakBytes()+" reserved="+budget.reservedBytes());
 }
}
