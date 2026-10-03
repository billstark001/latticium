package io.github.billstark001.latticium.dsl;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {
    static final ResourceId STONE=ResourceId.parse("minecraft:stone"), AIR=ResourceId.parse("minecraft:air"), OVERWORLD=ResourceId.parse("minecraft:overworld");
    static final Position ORIGIN=new Position(OVERWORLD,0,0,0);
    static final Registry REGISTRY=new Registry() {
        public Resolution resolve(SetType k,ResourceId id){return Set.of(STONE,AIR).contains(id)?Resolution.FOUND:Resolution.MISSING;}
        public Resolution resolveTag(SetType k,ResourceId id){return Resolution.MISSING;}
        public Set<ResourceId> tag(SetType k,ResourceId id){return Set.of();}
        public Set<ResourceId> universe(SetType k){return Set.of(STONE,AIR);}
        public Set<BlockState> states(ResourceId id){return Set.of(new BlockState(id,Map.of()));}
    };
    static final Facts FACTS=new Facts() {
        public Optional<WorldCell> world(Position p){return p.equals(ORIGIN)?Optional.of(new WorldCell(new BlockState(STONE,Map.of()),OVERWORLD,AIR,9,true)):Optional.empty();}
        public TargetCell target(Position p){return new TargetCell.Exact(new BlockState(AIR,Map.of()));}
        public Optional<Position> player(){return Optional.of(ORIGIN);}
        public Optional<Set<ResourceId>> inventory(){return Optional.of(Set.of(STONE));}
        public Truth selection(String n,Position p){return Truth.FALSE;}
    };
    @Test void unknownSurvivesNegationAndNeighborLookup() {
        var c=new Compiler(REGISTRY);
        assertEquals(Truth.UNKNOWN,c.compile("!current(s{minecraft:stone})",SetType.POS).at(FACTS,ORIGIN.offset(1,0,0)));
        assertEquals(Truth.TRUE,c.compile("offset(-1,0,0,current(b{stone}))",SetType.POS).at(FACTS,ORIGIN.offset(1,0,0)));
        assertEquals(Truth.UNKNOWN,c.compile("adjacent(current(b{stone}))",SetType.POS).at(FACTS,ORIGIN.offset(3,0,0)));
    }
    @Test void typesAndExpectedLiterals() {
        var c=new Compiler(REGISTRY);
        assertThrows(Syntax.Failure.class,()->c.compile("b{stone} & i{stone}",null));
        assertThrows(Syntax.Failure.class,()->c.compile("current({stone})",SetType.POS));
        assertEquals(SetType.ITEM,c.compile("{stone}",SetType.ITEM).type());
        assertEquals(Truth.TRUE,c.compile("minecraft:stone & y=..1",SetType.POS).at(FACTS,ORIGIN));
        assertEquals(SetType.POS,c.compile("state(x=1) | x=-64..32",SetType.POS).type());
    }
    @Test void documentDefinitionsAndTerminals() {
        var doc=Parser.document("rock: BlockSet = {stone}; def exposed(s: PosSet): PosSet = s & adjacent(!s); query exposed(current(rock)) limit any 3;");
        var bound=new Compiler(REGISTRY).compile(doc);
        assertEquals(1,bound.size());
        assertEquals(SetType.POS,bound.getFirst().type());
    }
    @Test void boundedIntegerFunctionParameterExpands() {
        var doc=Parser.document("def nearby(r: Int): PosSet = sphere(player,r); query nearby(2);");
        var bound=new Compiler(REGISTRY).compile(doc).getFirst();
        assertEquals(Truth.TRUE,bound.at(FACTS,ORIGIN.offset(2,0,0)));
        assertEquals(Truth.FALSE,bound.at(FACTS,ORIGIN.offset(3,0,0)));
        assertThrows(Syntax.Failure.class,()->new Compiler(REGISTRY).compile(Parser.document("def nearby(r: Int): PosSet = sphere(player,r); query nearby(40);")));
    }
    @Test void targetPhaseAndSymbolicPass() {
        assertThrows(Syntax.Failure.class,()->new Compiler(REGISTRY).compile("matches_target()",SetType.POS));
        assertEquals(Truth.FALSE,new Compiler(REGISTRY).targetAvailable(true).compile("matches_target()",SetType.POS).at(FACTS,ORIGIN));
        assertEquals(SetType.STATE,Compiler.symbolic().compile("s{example:mod_block[foo=bar]}",SetType.STATE).type());
    }
    @Test void trustedPrimitiveHasTypedSignatureAndLocality() {
        var primitive=new Compiler.Primitive() {
            public java.util.List<SetType> parameters(){return java.util.List.of(SetType.POS);}
            public SetType result(){return SetType.POS;}
            public int radius(){return 1;}
            public Compiler.Membership bind(java.util.List<Compiler.Bound> args){return (facts,pos,ignored)->args.getFirst().at(facts,pos.offset(1,0,0));}
        };
        var c=new Compiler(REGISTRY).registerPrimitive("east_of",primitive);
        var b=c.compile("east_of(current(b{stone}))",SetType.POS);
        assertEquals(1,b.radius());
        assertEquals(Truth.TRUE,b.at(FACTS,ORIGIN.offset(-1,0,0)));
        assertThrows(IllegalArgumentException.class,()->c.registerPrimitive("current",primitive));
    }
}
