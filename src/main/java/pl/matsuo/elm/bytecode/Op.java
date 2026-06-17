package pl.matsuo.elm.bytecode;

/** Opcodes for the stack-based Elm bytecode VM. */
public enum Op {
  PUSH_CONST, // operand: the constant value
  PUSH_VAR, // operand: unqualified name (local scope, else global)
  PUSH_QUAL, // operand: String[]{module, name}
  PUSH_CTOR, // operand: String[]{module, name} — module (may be null) resolves the defining module
  PUSH_OPFUNC, // operand: operator symbol
  PUSH_ACCESSOR, // operand: field name (.field as a function)
  MAKE_LIST, // arg: element count
  MAKE_TUPLE, // arg: element count
  MAKE_RECORD, // operand: String[] field names
  RECORD_UPDATE, // operand: String[] field names (base below the values)
  ACCESS, // operand: field name
  APPLY, // pop arg, pop fn, push (fn arg)
  BINOP, // operand: operator symbol
  NEGATE,
  JUMP, // arg: target instruction index
  JUMP_IF_FALSE, // arg: target; pops a Bool
  MAKE_CLOSURE, // operand: Chunk
  PUSH_SCOPE,
  POP_SCOPE,
  SET_SCRUT, // pop -> scrutinee register (for case)
  MATCH, // operand: Pattern, arg: jump target on failure
  BIND_PAT, // operand: Pattern; pop value and bind (irrefutable)
  ERROR, // operand: message
  TAIL_CALL, // arg: argument count; rebind the current chunk's params from the stack and jump to 0
  RETURN // return top of stack
}
