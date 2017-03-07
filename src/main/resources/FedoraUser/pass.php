#!/usr/bin/env drush
<?php
#define('DRUPAL_ROOT', getcwd());
require_once DRUPAL_ROOT . '/includes/bootstrap.inc';
drupal_bootstrap(DRUPAL_BOOTSTRAP_FULL);
require_once DRUPAL_ROOT . '/includes/password.inc';

$me = array_search('pass', $_SERVER['argv']);
# +2 to also skip over the --script-path
$my_args = array_slice($_SERVER['argv'], $me+2);

$user = user_load_by_name($my_args[0]);
if (empty($user)) {
        echo 'ERR: user['.$my_args[0].'] is unknown! ';
        exit(1);
}
#$newhash = user_hash_password($my_args[1]);
#$updatepass = db_update('users')->fields(array('pass' => $newhash))->condition('uid', $user->uid, '=')->execute();

#echo 'PASS: '.$newhash;
echo 'PASS: '.$user->pass;