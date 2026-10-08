package org.cgse.core;

import java.util.*;

/** Local parity between the integrated production policy and tested candidate. */
public final class ProductionPolicyParityProbe {
    static long checks,decisions;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError(checks);}
    public static void main(String[] args){
        for(int seed=0;seed<100;seed++){
            var reference=new CountPortfolioScalePolicy();var production=new CountPortfolioPolicy();
            var left=new ArrayList<CountPortfolioScalePolicy.Arm>();var right=new ArrayList<CountPortfolioPolicy.Arm>();
            Random random=new Random(seed);int size=2+random.nextInt(30);long[]scale=new long[size];
            for(int i=0;i<size;i++){long cost=random.nextInt(100);left.add(reference.add(cost));right.add(production.add(cost));scale[i]=1+random.nextInt(1000000000);}
            for(int t=0;t<3000;t++){
                var x=reference.select();var y=production.select();int id=left.indexOf(x);check(id==right.indexOf(y));
                long q=reference.quantum(x,100000);check(q==production.quantum(y,100000));
                reference.selected(x);production.selected(y);
                long progress=random.nextInt(5)==0?0:random.nextInt(10000),spent=q+random.nextInt(1500);
                reference.feedback(x,spent,progress);production.feedback(y,spent,Math.multiplyExact(progress,scale[id]));
                check(Double.doubleToLongBits(x.reward)==Double.doubleToLongBits(y.reward));
                check(x.work==y.work && x.idleSlices==y.idleSlices && x.productiveSlices==y.productiveSlices && x.selections==y.selections);
                check(x.reward>=0 && x.reward<=1 && Double.isFinite(y.reward));decisions++;
            }
        }
        var p=new CountPortfolioPolicy();var a=p.add(0);
        p.selected(a);p.feedback(a,Long.MAX_VALUE,Long.MAX_VALUE);
        p.selected(a);p.feedback(a,Long.MAX_VALUE,Long.MAX_VALUE-1);
        check(a.work==Long.MAX_VALUE && Double.isFinite(a.reward));
        double reward=a.reward;long best=a.bestProgress;
        p.selected(a);check(a.reward==reward && a.bestProgress==best); // cancelled: no feedback
        try{p.feedback(a,-1,5);throw new AssertionError();}catch(IllegalArgumentException expected){}
        check(a.reward==reward && a.bestProgress==best);
        System.out.println("{\"checks\":"+checks+",\"decisions\":"+decisions+",\"seeds\":100,\"production_candidate_parity\":true}");
    }
}
