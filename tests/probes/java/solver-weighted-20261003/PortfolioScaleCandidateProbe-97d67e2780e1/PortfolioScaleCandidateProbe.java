package org.cgse.core;

import java.util.*;

/** Exact deterministic observation replay. This does not run or certify a solver. */
public final class PortfolioScaleCandidateProbe {
    static long assertions, replayed;
    static void require(boolean value) { assertions++; if(!value)throw new AssertionError("assertion "+assertions); }
    public static void main(String[] args) {
        for(int seed=0;seed<100;seed++) replay(seed);
        coldStart(); stoppedObservations(); extremes(); cancelled();
        cost(false,20000);cost(true,20000);
        long baseline=cost(false,200000), candidate=cost(true,200000);
        System.out.println("{\"assertions\":"+assertions+",\"replayed_decisions\":"+replayed+",\"replay_seeds\":100,\"timed_updates\":200000,\"baseline_milliseconds\":"+baseline/1e6+",\"candidate_milliseconds\":"+candidate/1e6+"}");
    }
    static void replay(int seed) {
        var left=new CountPortfolioScalePolicy();var right=new CountPortfolioScalePolicy();
        var a=new ArrayList<CountPortfolioScalePolicy.Arm>();var b=new ArrayList<CountPortfolioScalePolicy.Arm>();
        Random random=new Random(seed);int size=2+random.nextInt(30);long[] scale=new long[size];
        for(int i=0;i<size;i++){long startup=random.nextInt(100);a.add(left.add(startup));b.add(right.add(startup));scale[i]=1+random.nextInt(1000000000);}
        for(int t=0;t<3000;t++) {
            var x=left.select();var y=right.select();int id=a.indexOf(x);require(id==b.indexOf(y));
            long q=left.quantum(x,100000);require(q==right.quantum(y,100000));
            left.selected(x);right.selected(y);
            // Zero plateaus, bursts and deterministic atomic overshoot are identical.
            long observation=random.nextInt(5)==0?0:random.nextInt(10000);
            long spent=q+random.nextInt(1500);
            left.feedback(x,spent,observation);right.feedback(y,spent,Math.multiplyExact(observation,scale[id]));
            require(Double.doubleToLongBits(x.reward)==Double.doubleToLongBits(y.reward));
            require(x.work==y.work && x.idleSlices==y.idleSlices && x.productiveSlices==y.productiveSlices);
            require(x.reward>=0 && x.reward<=1 && Double.isFinite(x.reward));
            replayed++;
        }
    }
    static void coldStart() {
        var p=new CountPortfolioScalePolicy();var arms=new ArrayList<CountPortfolioScalePolicy.Arm>();
        for(int i=0;i<12;i++)arms.add(p.add(11-i));
        for(int i=0;i<12;i++){var a=p.select();require(a==arms.get(11-i));p.selected(a);p.feedback(a,4096,Long.MAX_VALUE);}
        require(arms.stream().allMatch(a->a.selections==1));
        long[] previous=new long[12];Arrays.fill(previous,11);
        for(int turn=12;turn<500;turn++) {
            var a=p.select();int i=arms.indexOf(a);require(turn-previous[i]<=24+12);
            previous[i]=turn;p.selected(a);p.feedback(a,4096,i==0?Long.MAX_VALUE:0);
        }
        require(arms.stream().allMatch(a->a.selections>1));
    }
    static void stoppedObservations() {
        var p=new CountPortfolioScalePolicy();var arms=new ArrayList<CountPortfolioScalePolicy.Arm>();
        for(int i=0;i<4;i++)arms.add(p.add(1));
        for(int i=0;i<200;i++){var a=p.select();p.selected(a);p.feedback(a,4096,0);}
        require(arms.stream().allMatch(a->a.idleSlices>=2 && a.productiveSlices==0 && a.reward==0));
        require(arms.stream().allMatch(a->p.quantum(a,100000)==4096));
        var lone=new CountPortfolioScalePolicy();var a=lone.add(1);
        require(lone.quantum(a,100000)==32768);a.retired=true;require(lone.select()==null);
    }
    static void extremes() {
        var p=new CountPortfolioScalePolicy();var a=p.add(1);
        p.selected(a);p.feedback(a,Long.MAX_VALUE,Long.MAX_VALUE);
        require(a.reward==1 && a.work==Long.MAX_VALUE);
        p.selected(a);p.feedback(a,Long.MAX_VALUE,Long.MAX_VALUE-1);
        require(a.reward>0 && a.reward<=1 && a.work==Long.MAX_VALUE);
        p.selected(a);p.feedback(a,0,Long.MAX_VALUE);
        require(Double.isFinite(a.reward) && a.reward<=1);
        p.selected(a);p.feedback(a,Long.MAX_VALUE,1);
        require(Double.isFinite(a.reward) && a.reward>=0);
        p.selected(a);p.feedback(a,1,Long.MIN_VALUE);require(a.idleSlices==1);
        var q=new CountPortfolioScalePolicy();var b=q.add(1);
        q.selected(b);q.feedback(b,Long.MAX_VALUE,1);
        q.selected(b);q.feedback(b,Long.MAX_VALUE-1,1);require(b.reward==1);
    }
    static void cancelled() {
        var p=new CountPortfolioScalePolicy();var a=p.add(1);
        p.selected(a);p.feedback(a,4096,4);
        double reward=a.reward;long work=a.work,best=a.bestProgress;
        p.selected(a); // Caller cancellation happens before completedSlice; no feedback.
        require(a.reward==reward && a.work==work && a.bestProgress==best);
        try{p.feedback(a,-1,5);throw new AssertionError("negative work accepted");}catch(IllegalArgumentException expected){}
        require(a.reward==reward && a.work==work && a.bestProgress==best);
        p.feedback(a,4096,4);require(a.work==8192 && a.reward==reward);
    }
    static long cost(boolean candidate,int rounds) {
        long start=System.nanoTime();
        if(candidate){var p=new CountPortfolioScalePolicy();var a=new ArrayList<CountPortfolioScalePolicy.Arm>();for(int i=0;i<12;i++)a.add(p.add(i));for(int t=0;t<rounds;t++){var x=p.select();p.selected(x);p.feedback(x,4096+t%30000,t%100+1);}}
        else{var p=new CountPortfolioPolicy();var a=new ArrayList<CountPortfolioPolicy.Arm>();for(int i=0;i<12;i++)a.add(p.add(i));for(int t=0;t<rounds;t++){var x=p.select();p.selected(x);p.feedback(x,4096+t%30000,t%100+1);}}
        return System.nanoTime()-start;
    }
}
