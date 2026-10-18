grammar Sir;

// The two entry rules: a root document and a declaration fragment. The words sources, source, imports and import
// are contextual IDENT predicates, so they remain legal identifiers elsewhere.
projectDocument
    : SIR versionLiteral SOFTWARE IDENT LBRACE metadataBlock targetBlock
      sourcesBlock importsBlock declarationsBlock RBRACE EOF
    ;

sourceFragment
    : SIR versionLiteral importsBlock declarationsBlock EOF
    ;

sourcesBlock
    : {_input.LT(1).getText().equals("sources")}? IDENT LBRACE sourceEntry* RBRACE
    ;

sourceEntry
    : {_input.LT(1).getText().equals("source")}? IDENT STRING SEMI
    ;

importsBlock
    : {_input.LT(1).getText().equals("imports")}? IDENT LBRACE importEntry* RBRACE
    ;

importEntry
    : {_input.LT(1).getText().equals("import")}? IDENT declarationName=IDENT FROM STRING SEMI
    ;

versionLiteral
    : DOTTED_NUMBER
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
    | viewDecl
    | errorDecl
    | capabilityDecl
    | featureUseDecl
    | semanticTypeDecl
    | featureDefinitionDecl
    ;

// A feature of the author's own: its parameters, and the declarations it expands into written as text between
// triple backticks with ${parameter} where a parameter's value goes. "feature", "parameter" and "template" are
// contextual words, like use and type.
featureDefinitionDecl
    : {_input.LT(1).getText().equals("feature")}? IDENT featureName=IDENT LBRACE featureParameterDecl* featureTemplate RBRACE
    ;

featureParameterDecl
    : {_input.LT(1).getText().equals("parameter")}? IDENT parameterName=IDENT COLON parameterKind=IDENT
      (ASSIGN (durationOperand | defaultWord=IDENT | defaultEmpty=STRING))? SEMI
    ;

featureTemplate
    : {_input.LT(1).getText().equals("template")}? IDENT RAW_BLOCK SEMI
    ;

// "type Percent = Count where min(0), max(100);" names a meaning of the author's own: the kind of value and the
// rules of an existing semantic type, with the rules written here replacing the ones of the same name. "type" is
// contextual, like use.
semanticTypeDecl
    : {_input.LT(1).getText().equals("type")}? IDENT typeName=IDENT ASSIGN baseName=IDENT constraintList? SEMI
    ;

// "use DualTokenLogin { account: User; access: 15 minutes; }" asks for a language feature by name; the analysis
// expands it into ordinary declarations. "use" is contextual, like credential: no other declaration starts with an
// identifier, so every name spelled use stays legal. A value is a name (an entity, a field) or a duration.
featureUseDecl
    : {_input.LT(1).getText().equals("use")}? IDENT featureName=IDENT LBRACE featureArgument* RBRACE
    ;

featureArgument
    : parameter=IDENT COLON (durationOperand | value=IDENT) SEMI
    ;

enumDecl
    : ENUM IDENT LBRACE
        IDENT (COMMA IDENT)*
      RBRACE
    ;

entityDecl
    : ENTITY IDENT PERSISTENT LBRACE
        identityDecl
        entityMemberDecl*
        credentialClause?
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
    : INPUT IDENT (PATCH OF patchSourceEntity=IDENT)? LBRACE fieldDecl* RBRACE
    ;

viewDecl
    : VIEW viewName=IDENT FROM sourceEntity=IDENT LBRACE fieldDecl* RBRACE
    ;

fieldDecl
    : FIELD IDENT COLON typeRef constraintList? VERSIONED? SEMI
    ;

// An entity member is the only field the language lets carry a persistent declaration id.
// input/view fields deliberately keep the plain fieldDecl rule: a stray @id there is a parse
// error rather than a silently ignored annotation.
entityMemberDecl
    : FIELD IDENT COLON typeRef constraintList? VERSIONED? uniqueModifier? secretModifier? defaultModifier? idAnnotation? SEMI
    ;

// "credential subject owner expires expiresAt;" closes an entity whose rows are bearer credentials. Its three
// words are contextual: only here do they mean anything, so every identifier spelled credential, subject or
// expires stays legal. It follows the fields, where no member can start with an identifier.
credentialClause
    : {_input.LT(1).getText().equals("credential")}? IDENT
      {_input.LT(1).getText().equals("subject")}? IDENT subject=IDENT
      {_input.LT(1).getText().equals("expires")}? IDENT expires=IDENT SEMI
    ;

// "unique" is a contextual word, like sources and imports above: it is only a modifier here, after
// the field's type, constraints and versioned. A reserved token would turn every existing identifier
// spelled unique into a parse error, and the language adds this modifier without invalidating any
// document that already parses.
uniqueModifier
    : {_input.LT(1).getText().equals("unique")}? IDENT
    ;

// "secret" is contextual for the same reason as unique: it follows the field's type, constraints, versioned
// and unique, and every identifier spelled secret stays legal.
secretModifier
    : {_input.LT(1).getText().equals("secret")}? IDENT
    ;

// "default 0;" is what a create step gives the field when it does not bind it. Like "unique" and "secret" the word
// is contextual, so every identifier spelled default stays legal. A default is a value the field has without any
// request: a constant, an enum member, or the moment of creation.
defaultModifier
    : {_input.LT(1).getText().equals("default")}? IDENT
      (constantArgument | enumName=IDENT DOT member=IDENT | NOW LPAREN RPAREN)
    ;

idAnnotation
    : ID_ANNOTATION LPAREN STRING RPAREN
    ;

constraintList
    : WHERE constraintCall (COMMA constraintCall)*
    ;

constraintCall
    : IDENT
    | IDENT LPAREN constantArgumentList RPAREN
    ;

errorDecl
    : ERROR IDENT (HTTP INT)? SEMI
    ;

capabilityDecl
    : CAPABILITY IDENT idAnnotation? LBRACE
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
    | deleteStep
    | returnStep
    ;

validateStep
    : VALIDATE expression ELSE IDENT SEMI
    ;

// "by name: expr" loads by a field the entity declares unique; without it the expression is the
// identity, as before. A key field is told apart from an expression by its colon, which no expression
// contains.
loadStep
    : LOAD entity=IDENT BY (key=IDENT COLON)? expression AS result=IDENT ELSE error=IDENT SEMI
    ;

findStep
    : FIND entity=IDENT WHERE predicate=expression findOrderClause? findPageClause? AS result=IDENT SEMI
    ;

findOrderClause
    : ORDER BY orderKey (COMMA orderKey)*
    ;

orderKey
    : field=IDENT direction=(ASCENDING | DESCENDING)?
    ;

findPageClause
    : PAGE pageExpr=expression COMMA sizeExpr=expression ELSE error=IDENT
    ;

createStep
    : CREATE IDENT AS IDENT LBRACE binding* RBRACE
    ;

updateStep
    : UPDATE IDENT guardClause? LBRACE binding+ RBRACE
    ;

// "update row when <condition> else Error { ... }": the write happens only if the stored row still satisfies
// the condition when it is written, and the error is raised if it does not. "when" is a contextual word
// like sources and unique, so it stays a legal name everywhere else.
guardClause
    : {_input.LT(1).getText().equals("when")}? IDENT expression ELSE IDENT
    ;

persistStep
    : PERSIST IDENT (ELSE IDENT)? SEMI
    ;

// "delete row;" removes the stored row a load read. "delete" is a contextual word, like use and type, so it stays a
// legal name everywhere else.
deleteStep
    : {_input.LT(1).getText().equals("delete")}? IDENT target=IDENT SEMI
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
    | PAGE LT typeRef GT
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
    : stringMatchExpression ((EQ | NE) stringMatchExpression)?
    ;

stringMatchExpression
    : relationalExpression ((CONTAINS_LITERAL | matchesOperator) relationalExpression)?
    ;

// "stored matches candidate" asks whether a candidate text produces a stored secret. It is contextual: no
// expression is otherwise followed by an identifier here, so a name spelled matches elsewhere is unaffected.
matchesOperator
    : {_input.LT(1).getText().equals("matches")}? IDENT
    ;

relationalExpression
    : additiveExpression ((GT | GE | LT | LE) additiveExpression)?
    ;

// Plus and minus bind tighter than a comparison and looser than a prefix operator, and chain to the left.
// After an operator, "INT unit" is a time shift; a plain operand is arithmetic. The two are told apart by
// the unit word that follows the integer, which no expression is otherwise followed by.
additiveExpression
    : unaryExpression additiveStep*
    ;

additiveStep
    : (PLUS | MINUS) (durationOperand | unaryExpression)
    ;

durationOperand
    : INT durationUnit
    ;

// The unit words are contextual, like sources and unique: minutes stays a legal name everywhere else.
durationUnit
    : {_input.LT(1).getText().matches("seconds?|minutes?|hours?|days?")}? IDENT
    ;

unaryExpression
    : (NOT | MINUS) unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression (DOT IDENT)* (DOT PRESENT)?
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
    | anyPredicate
    | groupedExpression
    ;

groupedExpression
    : LPAREN expression RPAREN
    ;

anyPredicate
    : ANY LPAREN entity=IDENT COMMA conditions=expression RPAREN
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
VERSIONED       : 'versioned';
PRESENT         : 'present';
WHERE           : 'where';
INPUT           : 'input';
PATCH           : 'patch';
OF              : 'of';
VIEW            : 'view';
FROM            : 'from';
ORDER           : 'order';
ASCENDING       : 'ascending';
DESCENDING      : 'descending';
PAGE            : 'Page';
CONTAINS_LITERAL: 'containsLiteral';
ERROR           : 'error';
HTTP            : 'http';
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
ANY             : 'any';
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
ASSIGN          : '=';
RAW_BLOCK       : '```' .*? '```';
NE              : '!=';
GE              : '>=';
LE              : '<=';
GT              : '>';
LT              : '<';
MINUS           : '-';
PLUS            : '+';
// The persistent declaration-id annotation. '@' appears nowhere else in the language, so this
// token cannot collide with IDENT.
ID_ANNOTATION   : '@id';
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
