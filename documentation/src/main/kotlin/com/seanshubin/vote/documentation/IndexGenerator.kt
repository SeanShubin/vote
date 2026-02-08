package com.seanshubin.vote.documentation

class IndexGenerator {
    fun generate(): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>Vote System Documentation</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <div class=\"container\">")
        appendLine("    <h1>Vote System Documentation</h1>")
        appendLine("    <p class=\"subtitle\">Comprehensive documentation generated from a real scenario</p>")
        appendLine()

        appendLine("    <div class=\"intro\">")
        appendLine("      <h2>About This Documentation</h2>")
        appendLine("      <p>This documentation is generated automatically during the build process.</p>")
        appendLine("      <p>It shows the complete system state after running a comprehensive scenario that exercises every feature of the voting system.</p>")
        appendLine("      <p>The scenario demonstrates the happy path through the application, including:</p>")
        appendLine("      <ul>")
        appendLine("        <li>User registration and management</li>")
        appendLine("        <li>Election creation and configuration</li>")
        appendLine("        <li>Candidate and voter management</li>")
        appendLine("        <li>Ballot casting and updating</li>")
        appendLine("        <li>Election lifecycle (launch, finalize, delete)</li>")
        appendLine("      </ul>")
        appendLine("    </div>")

        appendLine("    <div class=\"links\">")

        appendLine("      <a href=\"schema.html\" class=\"link-card schema\">")
        appendLine("        <div class=\"link-icon\">📊</div>")
        appendLine("        <div class=\"link-title\">Data Model</div>")
        appendLine("        <div class=\"link-description\">Conceptual model showing entities and relationships (MySQL canonical representation)</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"scenario.html\" class=\"link-card scenario\">")
        appendLine("        <div class=\"link-icon\">📖</div>")
        appendLine("        <div class=\"link-title\">Scenario Summary</div>")
        appendLine("        <div class=\"link-description\">What happened: narrative of all actions taken in plain English</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"sql.html\" class=\"link-card sql\">")
        appendLine("        <div class=\"link-icon\">🗄️</div>")
        appendLine("        <div class=\"link-title\">MySQL Database Dump</div>")
        appendLine("        <div class=\"link-description\">Complete SQL database state with all tables and data</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"dynamodb.html\" class=\"link-card dynamodb\">")
        appendLine("        <div class=\"link-icon\">☁️</div>")
        appendLine("        <div class=\"link-title\">DynamoDB Dump</div>")
        appendLine("        <div class=\"link-description\">Single-table design showing PK/SK pattern and all items</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"events.html\" class=\"link-card events\">")
        appendLine("        <div class=\"link-icon\">📝</div>")
        appendLine("        <div class=\"link-title\">Event Log</div>")
        appendLine("        <div class=\"link-description\">Complete event sequence showing all domain events</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"http.html\" class=\"link-card http\">")
        appendLine("        <div class=\"link-icon\">🌐</div>")
        appendLine("        <div class=\"link-title\">HTTP API Documentation</div>")
        appendLine("        <div class=\"link-description\">All HTTP requests and responses with real data</div>")
        appendLine("      </a>")

        appendLine("      <a href=\"../code-structure/browse/index.html\" class=\"link-card code-structure\">")
        appendLine("        <div class=\"link-icon\">🔍</div>")
        appendLine("        <div class=\"link-title\">Code Structure Analysis</div>")
        appendLine("        <div class=\"link-description\">Dependency analysis, cycle detection, and package hierarchy</div>")
        appendLine("      </a>")

        appendLine("    </div>")

        appendLine("    <div class=\"footer\">")
        appendLine("      <p>Generated automatically by the documentation generator</p>")
        appendLine("      <p>Run <code>./gradlew :documentation:generateDocumentation</code> to regenerate</p>")
        appendLine("    </div>")

        appendLine("  </div>")
        appendLine("</body>")
        appendLine("</html>")
    }

    private fun css() = """
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            padding: 40px 20px;
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
        }
        h1 {
            color: white;
            font-size: 48px;
            margin-bottom: 10px;
            text-align: center;
            text-shadow: 2px 2px 4px rgba(0,0,0,0.2);
        }
        .subtitle {
            color: rgba(255,255,255,0.9);
            text-align: center;
            font-size: 18px;
            margin-bottom: 40px;
        }
        .intro {
            background: white;
            padding: 30px;
            border-radius: 12px;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
            margin-bottom: 40px;
        }
        .intro h2 {
            color: #333;
            margin-bottom: 15px;
        }
        .intro p {
            color: #666;
            line-height: 1.6;
            margin-bottom: 10px;
        }
        .intro ul {
            margin-left: 30px;
            margin-top: 15px;
        }
        .intro li {
            color: #666;
            margin-bottom: 8px;
        }
        .links {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 25px;
            margin-bottom: 40px;
        }
        .link-card {
            background: white;
            padding: 30px;
            border-radius: 12px;
            text-decoration: none;
            transition: transform 0.2s, box-shadow 0.2s;
            box-shadow: 0 4px 6px rgba(0,0,0,0.1);
            border-left: 5px solid;
        }
        .link-card:hover {
            transform: translateY(-5px);
            box-shadow: 0 8px 12px rgba(0,0,0,0.2);
        }
        .link-card.schema { border-left-color: #3498db; }
        .link-card.scenario { border-left-color: #27ae60; }
        .link-card.sql { border-left-color: #0066cc; }
        .link-card.dynamodb { border-left-color: #ff9900; }
        .link-card.events { border-left-color: #2ecc71; }
        .link-card.http { border-left-color: #9b59b6; }
        .link-card.code-structure { border-left-color: #e74c3c; }
        .link-icon {
            font-size: 48px;
            margin-bottom: 15px;
        }
        .link-title {
            font-size: 24px;
            font-weight: 600;
            color: #333;
            margin-bottom: 10px;
        }
        .link-description {
            font-size: 14px;
            color: #666;
            line-height: 1.5;
        }
        .footer {
            background: rgba(255,255,255,0.1);
            color: white;
            text-align: center;
            padding: 20px;
            border-radius: 12px;
        }
        .footer p {
            margin-bottom: 5px;
        }
        .footer code {
            background: rgba(0,0,0,0.2);
            padding: 3px 8px;
            border-radius: 4px;
            font-family: monospace;
        }
    """.trimIndent()
}
