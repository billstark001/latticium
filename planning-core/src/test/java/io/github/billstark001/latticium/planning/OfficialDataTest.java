package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.offline.ReportRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@Tag("official")
class OfficialDataTest {
    private static ReportRegistry load(String version) throws Exception {
        String root=System.getProperty("latticium.officialRoot");
        assertNotNull(root,"Run scripts/fetch_official_minecraft.py and scripts/bake_official_minecraft.py first");
        return ReportRegistry.load(Path.of(root,version,"catalog.json"));
    }

    @Test void bothVersionCatalogsHaveLegalStatesAndResolvedTags() throws Exception {
        for(String version:new String[]{"26.2","26.3"}) {
            var registry=load(version);
            assertEquals(version,registry.version());
            assertTrue(registry.universe(SetType.BLOCK).size()>1000);
            assertTrue(registry.universe(SetType.ITEM).size()>1000);
            assertTrue(registry.universe(SetType.BIOME).size()>50);
            for(var block:registry.universe(SetType.BLOCK)) {
                var states=registry.states(block);
                assertFalse(states.isEmpty(),block.toString());
                var schema=registry.stateSchema(block);
                for(var state:states) {
                    assertEquals(schema.keySet(),state.properties().keySet(),block.toString());
                    state.properties().forEach((key,value)->assertTrue(schema.get(key).contains(value),block+"["+key+"="+value+"]"));
                }
            }
            var logs=ResourceId.parse("minecraft:logs");
            assertEquals(Registry.Resolution.FOUND,registry.resolveTag(SetType.STATE,logs));
            assertTrue(registry.tag(SetType.STATE,logs).contains(ResourceId.parse("minecraft:oak_log")));
            assertTrue(registry.tag(SetType.BIOME,ResourceId.parse("minecraft:is_nether")).contains(ResourceId.parse("minecraft:basalt_deltas")));
        }
    }

    @Test void realRegistriesBindVersionScopedDslAndRejectMissingIds() throws Exception {
        var older=load("26.2");var newer=load("26.3");
        assertTrue(newer.universe(SetType.BLOCK).size()>older.universe(SetType.BLOCK).size());
        var added=newer.universe(SetType.BLOCK).stream().filter(id->!older.universe(SetType.BLOCK).contains(id)).findFirst().orElseThrow();
        assertThrows(io.github.billstark001.latticium.dsl.Syntax.Failure.class,()->new Compiler(older).compile("b{"+added+"}",SetType.BLOCK));
        assertEquals(SetType.BLOCK,new Compiler(newer).compile("b{"+added+"}",SetType.BLOCK).type());
        for(var registry:new ReportRegistry[]{older,newer}) {
            var c=new Compiler(registry);
            var stairs=c.compile("s{minecraft:oak_stairs[facing=north]}",SetType.STATE);
            assertTrue(registry.states(ResourceId.parse("minecraft:oak_stairs")).stream().anyMatch(s->stairs.contains(null,s)==Truth.TRUE));
            assertThrows(io.github.billstark001.latticium.dsl.Syntax.Failure.class,()->c.compile("s{minecraft:oak_stairs[facing=up]}",SetType.STATE));
            var logStates=c.compile("s{#minecraft:logs[axis=y]}",SetType.STATE);
            assertEquals(Truth.TRUE,logStates.contains(null,new BlockState(ResourceId.parse("minecraft:oak_log"),Map.of("axis","y"))));
            assertEquals(SetType.POS,c.compile("$#minecraft:is_nether & fluid(f{minecraft:lava})",SetType.POS).type());
        }
    }

    @Test void designProfileBindsInBothVersions() throws Exception {
        String json="""
            {"schema":1,"id":"user:basalt_lava",
             "activation":{"where":"$minecraft:basalt_deltas & dimension(minecraft:the_nether)","mode":"while"},
             "scope":"sphere(player, 5)",
             "select":{"where":"fluid({minecraft:lava}) & $minecraft:basalt_deltas"},
             "target":{"items":"{minecraft:stone, minecraft:dirt, minecraft:netherrack}",
                       "choose":{"prefer":["minecraft:stone","minecraft:dirt","minecraft:netherrack"]}},
             "policy":{"break":"deny","max_actions_per_tick":1,"max_actions_per_activation":256}}
            """;
        var reader=new ProfileReader();var profile=reader.read(json);
        for(String version:new String[]{"26.2","26.3"}) {
            var bound=reader.bind(profile,new Compiler(load(version)));
            assertEquals(SetType.ITEM,bound.items().type());
            assertEquals(SetType.POS,bound.select().type());
        }
    }
    @Test void ruleStatesAreCheckedAgainstBothOfficialStateTables() throws Exception {
        String valid="""
            {"schema":1,"rules":[{"id":"test:snow_growth",
              "before":{"block":"minecraft:snow","properties":{"layers":"1"}},
              "after":{"block":"minecraft:snow","properties":{"layers":"2"}},
              "action":"place","when":"current(s{minecraft:snow[layers=1]})",
              "verify":"current(s{minecraft:snow[layers=2]})"}]}
            """;
        for(String version:new String[]{"26.2","26.3"}) {
            var registry=load(version);
            assertEquals(1,RuleBook.parse(valid,new Compiler(registry),registry).rules().size());
            assertThrows(IllegalArgumentException.class,()->RuleBook.parse(valid.replace("\"layers\":\"2\"","\"layers\":\"0\""),new Compiler(registry),registry));
        }
    }
}
