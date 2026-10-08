package org.cgse.core;
import java.util.*;
public final class LoopProbe {
 public static void main(String[]args){for(boolean fuel:new boolean[]{false,true}){
 var rs=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",fuel?Map.of("A",1L,"fuel",1L):Map.of("A",1L),Map.of("C",1L)));
 var p=OrderProbe.solve(rs,3,Map.of("C",100L,"fuel",100L),true);System.out.println("LOOP fuel="+fuel+" result="+p.result()+" steps="+p.steps());
 }
 var bonus=List.of(OrderProbe.recipe("out",Map.of("C",1L),Map.of("A",1L)),OrderProbe.recipe("back",Map.of("A",1L,"fuel",1L),Map.of("C",1L,"bonus",1L)));
 var p=OrderProbe.solve(bonus,3,Map.of("C",100L,"fuel",3L),true);System.out.println("BONUS result="+p.result()+" steps="+p.steps());
 }
}
