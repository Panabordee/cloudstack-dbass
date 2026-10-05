import importlib.util,json,os,subprocess,tempfile,unittest
from unittest.mock import patch
from pathlib import Path

spec=importlib.util.spec_from_file_location('agent',Path(__file__).with_name('dbaas_agent.py'))
agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)

class PasswordResetTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.root=Path(self.directory.name)
        agent.ROLES_FILE=str(self.root/'roles.json')
        agent.ENGINE_FILE=str(self.root/'engine')
        agent.RESET_SCRIPT_DIR=str(self.root)
        (self.root/'engine').write_text('mysql.sh')
        (self.root/'mysql_reset.sh').write_text('#!/bin/sh\n')
        self.roles={'database':'second','owner':{'user':'other','password':'unchanged'},
          'databases':{'first':{'owner':{'user':'target','password':'old'},'readonly':{'user':'target_ro','password':'read-only'}},
                       'second':{'owner':{'user':'other','password':'unchanged'}}}}
        (self.root/'roles.json').write_text(json.dumps(self.roles))
        self.job={'type':'password_reset','payload':json.dumps({'db_user':'target','db_password':'new'})}
    def tearDown(self):self.directory.cleanup()
    def test_success_updates_target_database_only_and_survives_reload(self):
        with patch.object(agent.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'ok',b'')):
            result=agent.run_password_reset_job({},self.job,{})
        self.assertEqual(result[0],'confirmed')
        saved=json.loads((self.root/'roles.json').read_text())
        self.assertEqual(saved['databases']['first']['owner']['password'],'new')
        self.assertEqual(saved['databases']['first']['readonly']['password'],'read-only')
        self.assertEqual(saved['owner']['password'],'unchanged')
        role,db,error=agent.resolve_role(saved,{}, {'payload':json.dumps({'database':'first'}),'db_role':'owner'})
        self.assertEqual((role['password'],db,error),('new','first',''))
        self.assertEqual(os.stat(agent.ROLES_FILE).st_mode & 0o777,0o600)
    def test_failed_engine_reset_keeps_original_credentials(self):
        with patch.object(agent.subprocess,'run',return_value=subprocess.CompletedProcess([],1,b'',b'failed')):
            self.assertEqual(agent.run_password_reset_job({},self.job,{})[0],'failed')
        self.assertEqual(json.loads((self.root/'roles.json').read_text()),self.roles)
        self.assertEqual(list(self.root.glob('roles-reset-*')),[])
    def test_missing_cached_user_does_not_change_engine_password(self):
        self.job['payload']=json.dumps({'db_user':'unknown','db_password':'new'})
        with patch.object(agent.subprocess,'run') as run:
            self.assertEqual(agent.run_password_reset_job({},self.job,{})[0],'failed')
            run.assert_not_called()



class DatabaseDispatchTests(unittest.TestCase):
    def test_job_database_replaces_default_for_handler_only(self):
        original={'database':'second','vm_id':'vm'}
        captured=[]
        def handler(conf,job,role):
            captured.append(conf['database'])
            return 'confirmed',0,False,'',''
        with patch.dict(agent.JOB_HANDLERS,{'sql':handler}):
            agent.execute(original,{'type':'sql'},{'database':'first'})
        self.assertEqual(captured,['first'])
        self.assertEqual(original['database'],'second')

if __name__=='__main__':unittest.main()
