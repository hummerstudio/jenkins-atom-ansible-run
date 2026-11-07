# jenkins-atom-ansible-run

Ansible — port of **ansible** (16,443) as an atom.



## Build and test

```bash
mvn verify
```

`mvn verify` packages `dist/ansible-run/` and runs `src/test/java/atom/AnsibleRunIT.java` against that jar with a
fake `ansible-playbook` on the PATH.
