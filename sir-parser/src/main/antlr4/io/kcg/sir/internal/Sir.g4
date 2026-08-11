grammar Sir;

document
    : SIR versionLiteral softwareDecl EOF
    ;

versionLiteral
    : DOTTED_NUMBER
    ;

softwareDecl
    : SOFTWARE IDENT LBRACE
        metadataBlock
        targetBlock
        declarationsBlock
      RBRACE
    ;

metadataBlock
    : METADATA LBRACE
        DISPLAY_NAME STRING SEMI
        NAMESPACE STRING SEMI
      RBRACE
    ;

targetBlock
    : TARGET LBRACE
        LANGUAGE IDENT INT SEMI
        FRAMEWORK IDENT SEMI
        PERSISTENCE IDENT SEMI
        DATABASE IDENT SEMI
        BUILD IDENT SEMI
        INTERFACE IDENT SEMI
      RBRACE
    ;

declarationsBlock
    : DECLARATIONS LBRACE declaration* RBRACE
    ;

declaration
    : enumDecl
    | entityDecl
    | inputDecl
    | errorDecl
    | capabilityDecl
    ;

enumDecl
    : ENUM IDENT LBRACE
        IDENT (COMMA IDENT)*
      RBRACE
    ;

entityDecl
    : ENTITY IDENT PERSISTENT LBRACE
        identityDecl
        fieldDecl*
      RBRACE
    ;

identityDecl
    : IDENTITY IDENT COLON typeRef GENERATED generationStrategy SEMI
    ;

generationStrategy
    : AUTO
    | UUID_KW
    ;

inputDecl
    : INPUT IDENT LBRACE fieldDecl* RBRACE
    ;

fieldDecl
    : FIELD IDENT COLON typeRef constraintList? SEMI
    ;

constraintList
    : WHERE constraintCall (COMMA constraintCall)*
    ;

constraintCall
    : IDENT
    | IDENT LPAREN constantArgumentList RPAREN
    ;

errorDecl
    : ERROR IDENT SEMI
    ;

capabilityDecl
    : CAPABILITY IDENT LBRACE
        actorClause?
        inputClause?
        outputClause
        failsClause*
        requiresClause*
        exposeClause
        workflowDecl
      RBRACE
    ;

actorClause
    : ACTOR typeRef SEMI
    ;

inputClause
    : INPUT typeRef SEMI
    ;

outputClause
    : OUTPUT typeRef SEMI
    ;

failsClause
    : FAILS IDENT SEMI
    ;

requiresClause
    : REQUIRES requirement SEMI
    ;

requirement
    : AUTHENTICATED
    | ATOMIC
    | READONLY
    ;

exposeClause
    : EXPOSE (COMMAND | QUERY) SEMI
    ;

workflowDecl
    : WORKFLOW LBRACE workflowStep* RBRACE
    ;

workflowStep
    : validateStep
    | loadStep
    | findStep
    | createStep
    | updateStep
    | persistStep
    | returnStep
    ;

validateStep
    : VALIDATE expression ELSE IDENT SEMI
    ;

loadStep
    : LOAD IDENT BY expression AS IDENT ELSE IDENT SEMI
    ;

findStep
    : FIND IDENT WHERE expression AS IDENT SEMI
    ;

createStep
    : CREATE IDENT AS IDENT LBRACE binding* RBRACE
    ;

updateStep
    : UPDATE IDENT LBRACE binding+ RBRACE
    ;

persistStep
    : PERSIST IDENT SEMI
    ;

returnStep
    : RETURN expression SEMI
    ;

binding
    : IDENT COLON expression SEMI
    ;

typeRef
    : OPTIONAL LT typeRef GT
    | LIST LT typeRef GT
    | REF LT IDENT GT
    | IDENT
    ;

constantArgumentList
    : constantArgument (COMMA constantArgument)*
    ;

constantArgument
    : TRUE
    | FALSE
    | MINUS? decimalLiteral
    | MINUS? INT
    | STRING
    ;

expression
    : orExpression
    ;

orExpression
    : andExpression (OR andExpression)*
    ;

andExpression
    : equalityExpression (AND equalityExpression)*
    ;

equalityExpression
    : relationalExpression ((EQ | NE) relationalExpression)?
    ;

relationalExpression
    : unaryExpression ((GT | GE | LT | LE) unaryExpression)?
    ;

unaryExpression
    : (NOT | MINUS) unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression (DOT IDENT)*
    ;

primaryExpression
    : TRUE
    | FALSE
    | decimalLiteral
    | INT
    | STRING
    | UNIT
    | IDENT
    | INPUT
    | ACTOR
    | ITEM
    | NOW LPAREN RPAREN
    | groupedExpression
    ;

groupedExpression
    : LPAREN expression RPAREN
    ;

decimalLiteral
    : DOTTED_NUMBER
    ;

SIR             : 'sir';
SOFTWARE        : 'software';
METADATA        : 'metadata';
DISPLAY_NAME    : 'displayName';
NAMESPACE       : 'namespace';
TARGET          : 'target';
LANGUAGE        : 'language';
FRAMEWORK       : 'framework';
PERSISTENCE     : 'persistence';
DATABASE        : 'database';
BUILD           : 'build';
INTERFACE       : 'interface';
DECLARATIONS    : 'declarations';
ENUM            : 'enum';
ENTITY          : 'entity';
PERSISTENT      : 'persistent';
IDENTITY        : 'identity';
GENERATED       : 'generated';
AUTO            : 'auto';
UUID_KW         : 'uuid';
FIELD           : 'field';
WHERE           : 'where';
INPUT           : 'input';
ERROR           : 'error';
CAPABILITY      : 'capability';
ACTOR           : 'actor';
OUTPUT          : 'output';
FAILS           : 'fails';
REQUIRES        : 'requires';
AUTHENTICATED   : 'authenticated';
ATOMIC          : 'atomic';
READONLY        : 'readonly';
EXPOSE          : 'expose';
COMMAND         : 'command';
QUERY           : 'query';
WORKFLOW        : 'workflow';
VALIDATE        : 'validate';
ELSE            : 'else';
LOAD            : 'load';
BY              : 'by';
AS              : 'as';
FIND            : 'find';
ITEM            : 'item';
CREATE          : 'create';
UPDATE          : 'update';
PERSIST         : 'persist';
RETURN          : 'return';
OPTIONAL        : 'Optional';
LIST            : 'List';
REF             : 'Ref';
TRUE            : 'true';
FALSE           : 'false';
AND             : 'and';
OR              : 'or';
NOT             : 'not';
NOW             : 'now';
UNIT            : 'unit';

EQ              : '==';
NE              : '!=';
GE              : '>=';
LE              : '<=';
GT              : '>';
LT              : '<';
MINUS           : '-';
COLON           : ':';
SEMI            : ';';
COMMA           : ',';
DOT             : '.';
LPAREN          : '(';
RPAREN          : ')';
LBRACE          : '{';
RBRACE          : '}';

DOTTED_NUMBER
    : ('0' | [1-9] [0-9]*) '.' [0-9]+
    ;

INT
    : '0'
    | [1-9] [0-9]*
    ;

STRING
    : '"' (ESCAPE | ~["\\\u0000-\u001F])* '"'
    ;

INVALID_STRING
    : '"' ~["\r\n]* '"'
    ;

UNCLOSED_STRING
    : '"' ~["\r\n]* (EOF | '\r'? '\n')
    ;

fragment ESCAPE
    : '\\' ["\\nrtbf]
    ;

IDENT
    : [A-Za-z_] [A-Za-z0-9_]*
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> channel(HIDDEN)
    ;

BLOCK_COMMENT
    : '/*' (~[*] | '*' ~[/])* '*/' -> channel(HIDDEN)
    ;

UNCLOSED_BLOCK_COMMENT
    : '/*' (~[*] | '*' ~[/])* '*'? EOF
    ;

WS
    : ([ \t] | '\r'? '\n')+ -> channel(HIDDEN)
    ;
