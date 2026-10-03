package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Parser;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Explicit, bounded import graph for declaration-only DSL modules. */
public final class ModuleLoader {
    public record Module(String source,List<ResourceId> dependencies) {
        public Module {dependencies=List.copyOf(dependencies);}
    }
    public interface Resolver {Optional<Module> resolve(ResourceId id);}
    private final Resolver resolver;
    private final Compiler compiler;
    private final Set<ResourceId> loaded=new HashSet<>(),active=new HashSet<>();
    public ModuleLoader(Resolver resolver,Compiler compiler){this.resolver=resolver;this.compiler=compiler;}
    public void loadAll(List<ResourceId> ids){for(var id:ids)load(id,0);}
    private void load(ResourceId id,int depth) {
        if(loaded.contains(id))return;
        if(depth>64)throw new IllegalArgumentException("Import depth exceeded: "+id);
        if(!active.add(id))throw new IllegalArgumentException("Cyclic module import: "+id);
        try {
            var module=resolver.resolve(id).orElseThrow(()->new IllegalArgumentException("Missing module: "+id));
            for(var dependency:module.dependencies())load(dependency,depth+1);
            var doc=Parser.document(module.source());
            if(!doc.terminals().isEmpty())throw new IllegalArgumentException("Module cannot execute query: "+id);
            compiler.compile(doc);
            loaded.add(id);
        } finally {active.remove(id);}
    }
}
