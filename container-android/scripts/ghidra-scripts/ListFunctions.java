// ListFunctions.java — Ghidra脚本: 导出所有函数名和地址到文件
// 参数: 输出文件路径
//@category Cairn

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import java.io.*;

public class ListFunctions extends GhidraScript {
    @Override
    public void run() throws Exception {
        String outputPath = getScriptArgs().length > 0 ? getScriptArgs()[0] : "/tmp/functions.txt";
        FunctionManager fm = currentProgram.getFunctionManager();
        PrintWriter pw = new PrintWriter(new FileWriter(outputPath));
        
        for (Function func : fm.getFunctions(true)) {
            Address addr = func.getEntryPoint();
            String name = func.getName();
            long size = func.getBody().getNumAddresses();
            pw.println(String.format("0x%08X\t%s\t%d", addr.getOffset(), name, size));
        }
        pw.close();
        println("Exported " + fm.getFunctionCount() + " functions to " + outputPath);
    }
}
