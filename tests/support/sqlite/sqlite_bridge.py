import json
import sqlite3
import sys

connection = sqlite3.connect(':memory:', isolation_level=None)
for line in sys.stdin:
    try:
        request = json.loads(line)
        cursor = connection.execute(request['sql'], request.get('args', []))
        rows = cursor.fetchall() if cursor.description else []
        print(json.dumps({'rows': rows, 'id': cursor.lastrowid or 0, 'changed': cursor.rowcount}), flush=True)
    except Exception as failure:
        print(json.dumps({'error': str(failure)}), flush=True)
