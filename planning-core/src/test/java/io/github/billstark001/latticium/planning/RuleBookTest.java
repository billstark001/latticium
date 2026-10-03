package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RuleBookTest {
    @Test void declaredTransitionUsesSharedGuardAndPlanner() {
        String json="""
          {"schema":1,"rules":[{"id":"latticium:clear_stone","before":{"block":"minecraft:stone"},
          "after":{"block":"minecraft:air"},"action":"break","when":"current(b{minecraft:stone})",
          "verify":"current(b{minecraft:air}) & matches_target()"}]}
          """;
        var rules=RuleBook.parse(json,Compiler.symbolic());
        var dim=ResourceId.parse("minecraft:overworld");var pos=new Position(dim,0,0,0);
        var stone=new BlockState(ResourceId.parse("minecraft:stone"),Map.of());
        var facts=new Facts(){
            public Optional<WorldCell> world(Position p){return Optional.empty();}
            public TargetCell target(Position p){return new TargetCell.Clear();}
            public Optional<Position> player(){return Optional.empty();}
            public Optional<Set<ResourceId>> inventory(){return Optional.empty();}
            public Truth selection(String name,Position p){return Truth.FALSE;}
        };
        var planner=new Planner();
        assertInstanceOf(Planner.Result.NoPlan.class,planner.plan(pos,stone,new TargetCell.Clear(),new Profile.Policy(Profile.Policy.BreakMode.DENY,1,4),rules.oracle(facts),8));
        assertInstanceOf(Planner.Result.Ready.class,planner.plan(pos,stone,new TargetCell.Clear(),new Profile.Policy(Profile.Policy.BreakMode.SELECTED,1,4),rules.oracle(facts),8));
    }
    @Test void duplicateRuleIdRejected(){
        String rule="{\"id\":\"latticium:x\",\"before\":{\"block\":\"minecraft:stone\"},\"after\":{\"block\":\"minecraft:air\"},\"action\":\"break\"}";
        assertThrows(IllegalArgumentException.class,()->RuleBook.parse("{\"schema\":1,\"rules\":["+rule+","+rule+"]}",Compiler.symbolic()));
    }
}
