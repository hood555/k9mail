/* Generated By:JJTree: Do not edit this line. ASTroute.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=org.apache.james.mime4j.field.address.parser.BaseNode,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package org.apache.james.mime4j.field.address.parser;

public
class ASTroute extends SimpleNode {
  public ASTroute(int id) {
    super(id);
  }

  public ASTroute(AddressListParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(AddressListParserVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=bcec06c89402cfcb3700aefe8d5f14f9 (do not edit this line) */
