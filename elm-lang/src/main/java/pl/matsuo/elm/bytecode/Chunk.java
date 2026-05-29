package pl.matsuo.elm.bytecode;

import java.util.List;
import pl.matsuo.elm.ast.Pattern;

/** A compiled function/lambda body: its instructions, parameter patterns and a debug name. */
public record Chunk(List<Instr> code, List<Pattern> params, String name) {}
