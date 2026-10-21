"""Execute the smoke's real drift sequence against a lifecycle model, without Docker or HTTP."""
import ast
from pathlib import Path
from types import SimpleNamespace
import unittest


SOURCE = Path(__file__).with_name('deployment-smoke.py')


class DeploymentLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.application_running = True
        self.database_running = True
        self.application_ids = 'app-id'
        self.shapes = {'amount': (19, 2), 'rebate': (19, 2)}
        self.operations = []
        self.filters = []
        self.context = {
            'a': SimpleNamespace(instance='control-instance', v3_package='delivery-v3'),
            'acceptance_instance': 'acceptance-instance', 'acceptance_state': 'acceptance-state',
            'acceptance_port': 18081, 'report': {'decimalShape': 'NOT_RUN'},
            'subprocess': SimpleNamespace(check_output=self.check_output),
            'run': self.run_command, 'deploy': self.deploy,
        }
        self.tree = ast.parse(SOURCE.read_text(encoding='utf-8-sig'))
        functions = [node for node in self.tree.body if isinstance(node, ast.FunctionDef)
                     and node.name in ('database_container', 'sql', 'stop_application')]
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(SOURCE), 'exec'), self.context)

    def check_output(self, command, text):
        self.assertTrue(text)
        self.assertEqual(['docker', 'ps', '-q'], command[:3])
        self.assertIn('label=com.docker.compose.project=acceptance-instance', command)
        self.filters.append(command)
        if 'label=com.docker.compose.service=app' in command:
            return self.application_ids if self.application_running else ''
        self.assertIn('label=com.docker.compose.service=db', command)
        return 'db-id' if self.database_running else ''

    def run_command(self, command, expected=0):
        self.operations.append(command)
        self.assertEqual(0, expected)
        if command[:2] == ['docker', 'stop']:
            self.assertEqual(['app-id'], command[2:])
            self.application_running = False
            return ''
        self.assertEqual(['docker', 'exec', 'db-id'], command[:3], 'the database must remain running for drift SQL')
        self.assertTrue(self.database_running)
        self.assertFalse(self.application_running, 'stop the writer before altering its schema')
        sql = command[-1]
        self.assertTrue(sql.startswith('ALTER TABLE favorite MODIFY '), sql)
        column = sql.split()[4]
        precision, scale = sql.split('DECIMAL(', 1)[1].split(')', 1)[0].split(',')
        self.shapes[column] = (int(precision), int(scale))
        return ''

    def deploy(self, action, expected=0, **options):
        self.assertEqual('acceptance-instance', options['instance'])
        self.operations.append(('deploy', action, expected))
        if action == 'stop':
            # Product stop intentionally stops BOTH services. The smoke must not use it here.
            self.application_running = self.database_running = False
            return ''
        self.assertEqual('start', action)
        self.assertTrue(self.database_running)
        drifted = any(shape != (19, 2) for shape in self.shapes.values())
        self.assertEqual(3 if drifted else 0, expected)
        self.application_running = not drifted
        return 'decimal precision/scale mismatch' if drifted else 'RUNNING'

    def drift_sequence(self):
        main = next(node for node in self.tree.body if isinstance(node, ast.Try))
        first = next(index for index, node in enumerate(main.body)
                     if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                     and isinstance(node.value.func, ast.Name) and node.value.func.id == 'sql'
                     and node.value.args and isinstance(node.value.args[0], ast.Constant)
                     and str(node.value.args[0].value).startswith('ALTER TABLE favorite MODIFY amount'))
        last = next(index for index in range(first, len(main.body))
                    if isinstance(main.body[index], ast.Assign)
                    and ast.unparse(main.body[index].targets[0]) == "report['decimalShape']")
        return ast.Module(body=main.body[first - 1:last + 1], type_ignores=[])

    def test_drift_stops_only_the_writer_and_restores_both_shapes(self):
        exec(compile(self.drift_sequence(), str(SOURCE), 'exec'), self.context)
        self.assertEqual('PASSED', self.context['report']['decimalShape'])
        self.assertEqual({'amount': (19, 2), 'rebate': (19, 2)}, self.shapes)
        self.assertTrue(self.database_running)
        self.assertTrue(self.application_running)
        self.assertEqual([('deploy', 'start', 3), ('deploy', 'start', 3), ('deploy', 'start', 0)],
                         [operation for operation in self.operations if operation[0] == 'deploy'])
        self.assertEqual(4, sum(operation[:2] == ['docker', 'exec'] for operation in self.operations))

    def test_missing_or_ambiguous_application_is_not_silently_accepted(self):
        for ids in ('', 'app-id\nother-app-id'):
            with self.subTest(application_ids=ids):
                self.application_ids = ids
                with self.assertRaisesRegex(AssertionError, 'one running application container'):
                    exec(compile(self.drift_sequence(), str(SOURCE), 'exec'), self.context)
                self.assertTrue(self.database_running)
                self.assertEqual([], self.operations)


if __name__ == '__main__':
    unittest.main()
