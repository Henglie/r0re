// DecompileToStdout.java — Ghidra脚本: 反编译指定函数到stdout
// 参数: 函数名
//@category Cairn

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.symbol.Symbol;

public class DecompileToStdout extends GhidraScript {
    @Override
    public void run() throws Exception {
        String funcName = getScriptArgs().length > 0 ? getScriptArgs()[0] : "main";
        
        DecompInterface decompiler = new DecompInterface();
        decompiler.openCurrentProgram(currentProgram);
        
        FunctionManager fm = currentProgram.getFunctionManager();
        
        // Try exact match first
        Function target = null;
        for (Function func : fm.getFunctions(true)) {
            if (func.getName().equals(funcName)) {
                target = func;
                break;
            }
        }
        
        // Try contains match
        if (target == null) {
            for (Function func : fm.getFunctions(true)) {
                if (func.getName().contains(funcName)) {
                    target = func;
                    break;
                }
            }
        }
        
        if (target == null) {
            println("Function not found: " + funcName);
            return;
        }
        
        DecompileResults results = decompiler.decompileFunction(target, 60, monitor);
        if (results.decompileCompleted()) {
            println(results.getDecompiledFunction().getC());
        } else {
            println("Decompilation failed for: " + funcName);
        }
        
        decompiler.dispose();
    }
}
